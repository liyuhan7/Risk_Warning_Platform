"""在准确目标快照成功后覆盖开发知识库，保留其他索引与现有mapping。"""
import json
import hashlib
from datetime import datetime
from pathlib import Path
from init_es import http, ES_BASE_URL, INDEX_SEED_FILES, bulk_import, verify_index_mapping


def request(method, path, body=None):
    status, text = http(method, ES_BASE_URL + path, body)
    if status not in {200, 201}:
        raise RuntimeError(f"ES请求失败 {method} {path}: {status} {text[:300]}")
    return json.loads(text)


def main():
    targets = list(INDEX_SEED_FILES)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output = Path("documents/local/es-sync") / stamp
    output.mkdir(parents=True, exist_ok=False)
    manifest = {"targets": targets, "sourceSha256": {}, "previousIds": {}, "protectedIndices": {}}
    sources = {}
    for index in targets:
        path = Path(INDEX_SEED_FILES[index])
        sources[index] = json.loads(path.read_text(encoding="utf-8"))
        ids = [row["id"] for row in sources[index]]
        if not ids or len(set(ids)) != len(ids):
            raise ValueError("业务库为空或编号重复")
        manifest["sourceSha256"][str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
        verify_index_mapping(index, "ik_max_word")
        mapping = request("GET", f"/{index}/_mapping")[index]["mappings"]["properties"]
        if mapping["retrievalVector"].get("dims") != 1024:
            raise ValueError("现有索引向量维度不匹配，停止覆盖")
        hits = request("POST", f"/{index}/_search?scroll=1m", {"size": 1000, "_source": False, "sort": ["_doc"]})
        old = []
        while hits["hits"]["hits"]:
            old.extend(hit["_id"] for hit in hits["hits"]["hits"])
            hits = request("POST", "/_search/scroll", {"scroll": "1m", "scroll_id": hits["_scroll_id"]})
        request("DELETE", "/_search/scroll", {"scroll_id": [hits["_scroll_id"]]})
        manifest["previousIds"][index] = old
    for item in request("GET", "/_cat/indices?format=json"):
        if item["index"] not in targets:
            manifest["protectedIndices"][item["index"]] = request("GET", f"/{item['index']}/_count")["count"]
    repository = "candidate_index_backup"
    request("POST", f"/_snapshot/{repository}/_verify")
    snapshot = "development-knowledge-" + stamp
    saved = request("PUT", f"/_snapshot/{repository}/{snapshot}?wait_for_completion=true",
                    {"indices": ",".join(targets), "include_global_state": False})
    state = request("GET", f"/_snapshot/{repository}/{snapshot}")["snapshots"][0]
    if state["state"] != "SUCCESS" or set(state["indices"]) != set(targets) or state["shards"]["failed"]:
        raise RuntimeError("目标索引快照未完整成功，禁止覆盖")
    manifest["snapshot"] = {"repository": repository, "name": snapshot, "state": state["state"]}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print("backup", output, manifest["snapshot"], flush=True)
    for name, expected in manifest["sourceSha256"].items():
        if hashlib.sha256(Path(name).read_bytes()).hexdigest() != expected:
            raise RuntimeError("导入源文件在备份期间变化，停止覆盖")
    for index in targets:
        current_ids = {row["id"] for row in sources[index]}
        obsolete = sorted(set(manifest["previousIds"][index]) - current_ids)
        for offset in range(0, len(obsolete), 500):
            result = request("POST", f"/{index}/_delete_by_query?refresh=true",
                             {"query": {"ids": {"values": obsolete[offset:offset + 500]}}})
            if result.get("failures") or result.get("version_conflicts") or result.get("timed_out"):
                raise RuntimeError("旧候选删除未完整成功，停止同步")
        bulk_import(index, INDEX_SEED_FILES[index])
    for index, count in manifest["protectedIndices"].items():
        if request("GET", f"/{index}/_count")["count"] != count:
            raise RuntimeError("非目标索引数量发生变化：" + index)
    print("同步完成；检索向量及文本仍须由Knowledge回填。", flush=True)


if __name__ == "__main__":
    main()
