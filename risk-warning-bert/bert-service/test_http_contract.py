"""真实本地 HTTP 契约测试：导入时替换模型模块，不下载或加载模型。"""
import importlib.util
import sys
import threading
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest
import requests
from werkzeug.serving import make_server


class Classifier:
    def __init__(self):
        self.calls = []
        self.failure = False

    def classify(self, text, input_type):
        self.calls.append((text, input_type))
        if self.failure:
            raise RuntimeError("private-classifier")
        return {"tags": ["controlled"], "type": input_type, "dimension": "controlled"}

    def classify_batch(self, texts, input_types):
        return [self.classify(text, kind) for text, kind in zip(texts, input_types)]


@pytest.fixture(scope="module")
def server():
    patches = pytest.MonkeyPatch()
    # 屏蔽推理库及分类模块导入；测试只能使用明确的本地替身。
    loader = ModuleType("model.model_loader")
    loader.ModelLoader = SimpleNamespace()
    model_package = ModuleType("model")
    model_package.__path__ = []
    for name, module in {"model": model_package, "model.model_loader": loader}.items():
        patches.setitem(sys.modules, name, module)
    patches.setattr(sys, "path", list(sys.path))
    spec = importlib.util.spec_from_file_location("controlled_bert_http_service", Path(__file__).with_name("bert_service.py"))
    service = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(service)
    http_server = make_server("127.0.0.1", 0, service.app, threaded=True)
    thread = threading.Thread(target=http_server.serve_forever, daemon=True)
    thread.start()
    try:
        yield service, f"http://127.0.0.1:{http_server.server_port}"
    finally:
        http_server.shutdown()
        thread.join(timeout=5)
        http_server.server_close()
        patches.undo()
        assert not thread.is_alive()


@pytest.fixture
def runtime(server):
    service, url = server
    service.classifier_loader = Classifier()
    return service, url


def error(response, status):
    assert response.status_code == status, response.text
    assert response.headers["Content-Type"].startswith("application/json")
    body = response.json()
    assert set(body) == {"code", "message", "data"}
    assert body["code"] == status
    assert body["data"] is None
    assert "private-" not in response.text
    return body


def post(url, path, body):
    return requests.post(url + path, json=body, timeout=5)


def test_success_protocols_and_order(runtime):
    service, url = runtime
    response = post(url, "/classify", {"text": " controlled ", "input_type": " INDICATOR "})
    assert response.status_code == 200
    assert response.json() == {"tags": ["controlled"], "type": "indicator", "dimension": "controlled"}
    response = post(url, "/classify/batch", {"items": [{"text": "one"}, {"text": " "},
        {"text": "skip", "input_type": "unsupported"}, {"text": "two", "input_type": "regulation"}]})
    assert response.status_code == 200
    assert [item["type"] for item in response.json()["results"]] == ["behavior", "regulation"]
    assert service.classifier_loader.calls == [("controlled", "indicator"), ("one", "behavior"), ("two", "regulation")]


@pytest.mark.parametrize("path,body", [
    ("/classify", {}), ("/classify", {"text": " "}), ("/classify", {"text": None}),
    ("/classify", {"text": 1}), ("/classify", {"text": "valid", "input_type": None}),
    ("/classify", {"text": "valid", "input_type": "invalid"}),
    ("/classify/batch", {}), ("/classify/batch", {"items": []}),
    ("/classify/batch", {"items": 1}), ("/classify/batch", {"items": [None]}),
    ("/classify/batch", {"items": [{"text": "valid"}, {"text": 1}]}),
    ("/classify/batch", {"items": [{"text": "valid"}, {"input_type": None}]}),
    ("/classify/batch", {"items": [{}]}),
])
def test_parameters_rejected_before_inference(runtime, path, body):
    service, url = runtime
    error(post(url, path, body), 400)
    assert service.classifier_loader.calls == []


@pytest.mark.parametrize("path", ["/classify", "/classify/batch"])
def test_framework_json_errors(runtime, path):
    _, url = runtime
    for body in ["null", "[]", "1", "{"]:
        error(requests.post(url + path, data=body, headers={"Content-Type": "application/json"}, timeout=5), 400)
    error(requests.post(url + path, data="{}", headers={"Content-Type": "text/plain"}, timeout=5), 415)


@pytest.mark.parametrize("path,body", [
    ("/classify", {"text": "controlled"}), ("/classify/batch", {"items": [{"text": "controlled"}]}),
])
def test_missing_model_is_unavailable(runtime, path, body):
    service, url = runtime
    service.classifier_loader = None
    error(post(url, path, body), 503)


@pytest.mark.parametrize("path,body,component", [
    ("/classify", {"text": "controlled"}, "classifier_loader"),
    ("/classify/batch", {"items": [{"text": "controlled"}]}, "classifier_loader"),
])
def test_execution_faults_are_sanitized(runtime, path, body, component):
    service, url = runtime
    getattr(service, component).failure = True
    error(post(url, path, body), 500)


def test_invalid_classifier_results_are_server_errors(runtime, monkeypatch):
    service, url = runtime
    monkeypatch.setattr(service.classifier_loader, "classify", lambda *_: None)
    error(post(url, "/classify", {"text": "controlled"}), 500)
    monkeypatch.setattr(service.classifier_loader, "classify_batch", lambda *_: [])
    error(post(url, "/classify/batch", {"items": [{"text": "controlled"}]}), 500)


def test_health_and_framework_routes(runtime):
    service, url = runtime
    for loaded in [True, False]:
        if not loaded:
            service.classifier_loader = None
        response = requests.get(url + "/health", timeout=5)
        assert response.status_code == 200
        assert response.json() == {"status": "ok", "classifier_model_loaded": loaded}
        response = requests.get(url + "/classify/health", timeout=5)
        assert response.status_code == 200
        assert response.json() == {"status": "ok", "model_loaded": loaded}
    error(requests.get(url + "/missing", timeout=5), 404)
    response = requests.get(url + "/classify", timeout=5)
    error(response, 405)
    assert "POST" in response.headers["Allow"]


@pytest.mark.parametrize("path", ["/encode", "/vectorize-single"])
def test_removed_vector_routes_are_not_available(runtime, path):
    _, url = runtime
    error(post(url, path, {"text": "controlled", "texts": ["controlled"]}), 404)
