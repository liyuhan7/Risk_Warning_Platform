"""
事实分类服务
提供 HTTP 分类与健康检查接口
"""
from flask import Flask, request, jsonify
from flask_cors import CORS
from werkzeug.exceptions import BadRequest, HTTPException, ServiceUnavailable
import logging
import os
import sys
from pathlib import Path

# 配置日志（先初始化，以便后续使用）
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# 添加classify-service目录到路径
classify_service_path = Path(__file__).parent.parent / "classify-service"
classify_service_path_str = str(classify_service_path)
if classify_service_path.exists():
    sys.path.insert(0, classify_service_path_str)
    logger.info(f"已添加分类服务路径: {classify_service_path_str}")
else:
    logger.warning(f"分类服务目录不存在: {classify_service_path_str}")

# 导入分类服务模块（如果导入失败，分类功能不可用，但不影响BERT服务）
ModelLoader = None
try:
    from model.model_loader import ModelLoader
    logger.info("分类服务模块导入成功")
except Exception as e:
    logger.warning(f"分类服务模块导入失败: {e}，分类功能将不可用")
    import traceback
    logger.debug(traceback.format_exc())

app = Flask(__name__)
CORS(app)  # 允许跨域

classifier_loader = None


def load_classifier():
    """加载分类模型"""
    global classifier_loader
    if ModelLoader is None:
        logger.warning("分类服务模块未导入，分类功能不可用")
        classifier_loader = None
        return
    
    try:
        # 默认模型路径：classify-service/checkpoints/best_model.pt
        default_model_path = str(classify_service_path / "checkpoints" / "best_model.pt")
        model_path = os.getenv("CLASSIFIER_MODEL_PATH", default_model_path)
        model_name = os.getenv("CLASSIFIER_MODEL_NAME", "hfl/chinese-roberta-wwm-ext")
        device = os.getenv("DEVICE", "cpu")
        
        logger.info(f"Loading classifier model: {model_name}")
        logger.info(f"Classifier model path: {model_path}")
        
        classifier_loader = ModelLoader(
            model_path=model_path,
            model_name=model_name,
            device=device
        )
        
        logger.info("Classifier model loaded successfully")
    except Exception as e:
        logger.error(f"Failed to load classifier model: {e}", exc_info=True)
        # 分类不可用时保留健康查询，分类请求返回服务不可用
        classifier_loader = None

def error_response(status, message):
    """错误状态与 envelope 保持一致，不暴露内部异常或模型输入。"""
    return jsonify({"code": status, "message": message, "data": None}), status


@app.errorhandler(HTTPException)
def handle_http_error(error):
    messages = {
        400: "请求参数或 JSON 格式错误",
        404: "请求的资源不存在",
        405: "请求方法不支持",
        415: "请求内容类型不支持",
        503: "模型服务暂时不可用",
    }
    response, status = error_response(error.code, messages.get(error.code, "请求处理失败"))
    # 保留方法不支持等框架响应的协议头，内容类型由 JSON envelope 决定。
    for name, value in error.get_headers():
        if name.lower() != "content-type":
            response.headers.add(name, value)
    return response, status


@app.errorhandler(Exception)
def handle_execution_error(error):
    logger.exception("模型服务请求执行失败")
    return error_response(500, "请求处理失败")


def json_object():
    """保留框架对格式错误和内容类型的 400/415 区分。"""
    data = request.get_json()
    if not isinstance(data, dict):
        raise BadRequest()
    return data


def classification_item(item):
    """批量仍跳过空文本或不支持类型，错误字段类型不能进入模型。"""
    if not isinstance(item, dict):
        raise BadRequest()
    text = item.get("text", "")
    input_type = item.get("input_type", "behavior")
    if not isinstance(text, str) or not isinstance(input_type, str):
        raise BadRequest()
    return text.strip(), input_type.strip().lower()


@app.route('/health', methods=['GET'])
def health():
    """健康入口报告加载状态，未加载模型不影响状态查询。"""
    return jsonify({
        "status": "ok",
        "classifier_model_loaded": classifier_loader is not None
    })


@app.route('/classify/health', methods=['GET'], strict_slashes=False)
def classify_health():
    return jsonify({"status": "ok", "model_loaded": classifier_loader is not None})


@app.route('/classify/batch', methods=['POST'], strict_slashes=False)
def classify_batch():
    items = json_object().get("items")
    if not isinstance(items, list) or not items:
        raise BadRequest()
    texts = []
    input_types = []
    for item in items:
        text, input_type = classification_item(item)
        if text and input_type in ("behavior", "indicator", "regulation"):
            texts.append(text)
            input_types.append(input_type)
    if not texts:
        raise BadRequest()
    if classifier_loader is None:
        raise ServiceUnavailable()
    results = classifier_loader.classify_batch(texts, input_types)
    if not isinstance(results, list) or len(results) != len(texts):
        raise RuntimeError("分类结果数量与有效输入不符")
    return jsonify({"results": results})


@app.route('/classify', methods=['POST'], strict_slashes=False)
def classify():
    text, input_type = classification_item(json_object())
    if not text or input_type not in ("behavior", "indicator", "regulation"):
        raise BadRequest()
    if classifier_loader is None:
        raise ServiceUnavailable()
    result = classifier_loader.classify(text, input_type)
    if not isinstance(result, dict):
        raise RuntimeError("分类结果必须为对象")
    return jsonify(result)

if __name__ == '__main__':
    # 加载分类模型
    load_classifier()
    
    # 启动服务
    port = int(os.getenv("PORT", 8000))
    host = os.getenv("HOST", "0.0.0.0")
    logger.info(f"Starting service on {host}:{port}")
    app.run(host=host, port=port, debug=False)

