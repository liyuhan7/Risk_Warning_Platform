"""核对开发知识库全量业务字段、检索持久化及真实候选查询。"""
import hashlib
import json
import sys
from pathlib import Path
from urllib.request import Request, urlopen
from sync_es_knowledge import request
from init_es import INDEX_SEED_FILES


def main():
    output = Path(sys.argv[1])
    output.mkdir(parents=True, exist_ok=True)
    report = {"indices": {}, "queries": []}
    for index, filename in INDEX_SEED_FILES.items():
        expected = {row["id"]: row for row in json.loads(Path(filename).read_text(encoding="utf-8"))}
        request("POST", f"/{index}/_refresh")
        fields = ["retrievalText", "retrievalTextIk", "retrievalVector", "retrievalTextHash", "embeddingModel", "embeddingVersion"]
        counts = request("POST", f"/{index}/_search", {"size": 0, "aggs": {
            field: {"filter": {"exists": {"field": field}}} for field in fields}})
        coverage = {field: counts["aggregations"][field]["doc_count"] for field in fields}
        if any(count != len(expected) for count in coverage.values()):
            raise RuntimeError("检索字段未全量覆盖：" + str(coverage))
        hits = request("POST", f"/{index}/_search?scroll=1m", {
            "size": 500, "sort": ["_doc"], "_source": {"excludes": ["retrievalVector"]}})
        seen, metadata = set(), set()
        while hits["hits"]["hits"]:
            for hit in hits["hits"]["hits"]:
                iid, actual = hit["_id"], hit["_source"]
                if iid not in expected or any(actual.get(key) != value for key, value in expected[iid].items()):
                    raise RuntimeError("业务字段与交付不符：" + iid)
                seen.add(iid)
                if (not actual["retrievalText"].strip() or actual["retrievalTextIk"] != actual["retrievalText"]
                        or hashlib.sha256(actual["retrievalText"].encode()).hexdigest() != actual["retrievalTextHash"]):
                    raise RuntimeError("检索文本及摘要不一致：" + iid)
                metadata.add((actual["embeddingModel"], actual["embeddingVersion"]))
            hits = request("POST", "/_search/scroll", {"scroll": "1m", "scroll_id": hits["_scroll_id"]})
        request("DELETE", "/_search/scroll", {"scroll_id": [hits["_scroll_id"]]})
        if seen != set(expected) or len(metadata) != 1:
            raise RuntimeError("知识编号或Embedding版本未统一")
        sample = request("POST", f"/{index}/_search", {"size": 1, "_source": ["retrievalVector"]})
        if len(sample["hits"]["hits"][0]["_source"]["retrievalVector"]) != 1024:
            raise RuntimeError("持久化向量非1024维")
        report["indices"][index] = {"rows": len(seen), "coverage": coverage,
                                   "embeddingMetadata": sorted(metadata), "businessFieldsMatch": True}
    texts = ["核心物流服务商年度准时交付率不得低于95%", "银行贷款本金利息偿还计划与实际收款记录对应", "医疗器械设计变更应记录产品版本及验证资料"]
    body = {"scope": {"projectId": 1, "assessmentId": 1, "analysisRunId": "knowledge-sync-query-check"},
            "requests": [{"queryText": text, "behaviorId": "query-check-" + str(i), "analysisRunId": "knowledge-sync-query-check",
                          "assessmentId": 1, "indicatorTopK": 10, "regulationTopK": 10} for i, text in enumerate(texts)]}
    response = json.load(urlopen(Request("http://localhost:8089/retrieval/batch", data=json.dumps(body).encode(),
                                       headers={"Content-Type": "application/json"}), timeout=180))
    (output / "retrieval-query-results.json").write_text(json.dumps(response, ensure_ascii=False, indent=2), encoding="utf-8")
    for text, item in zip(texts, response["items"]):
        candidates = item["candidates"]
        types = {candidate["result"]["candidateType"] for candidate in candidates}
        if item["status"] != "SUCCESS" or not {"INDICATOR", "REGULATION"} <= types:
            raise RuntimeError("真实候选查询未同时返回指标和法规")
        report["queries"].append({"text": text, "status": item["status"], "returned": len(candidates)})
    keyword = request("POST", "/t_regulation/_search", {"size": 10, "_source": ["name"],
                                                       "query": {"match": {"retrievalTextIk": texts[0]}}})
    if not any(hit["_id"] == "lwUtUZ0B3BXzX1tXFWMT" for hit in keyword["hits"]["hits"]):
        raise RuntimeError("物流准时交付率关键词查询未命中")
    report["logisticsKeywordMatched"] = True
    report["passed"] = True
    (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
