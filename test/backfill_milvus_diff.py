# -*- coding: utf-8 -*-
"""Milvus 差集补数：只补 ES 有而 Milvus 缺的法规向量。

ES t_regulation 4865 条 vs Milvus regulation_vectors 4765 条，缺口约 100 条
（2025-12-04 批次 from/size 深分页漏推）。本脚本计算差集，仅对缺失的
es_id 调 knowledge 服务 batch-store 接口（bert 向量化 + 写入）。
"""
import io
import sys
import requests

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ES = "http://127.0.0.1:9200"
GATEWAY = "http://127.0.0.1:8088"
BATCH_STORE = GATEWAY + "/api/knowledge/test/vectorization/batch-store"

TOKEN = open(r"C:\Users\24125\AppData\Local\Temp\jwt_token.txt", encoding="utf-8").read().strip()
HEADERS = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}

SCROLL_TTL = "2m"
BATCH = 50


def scroll_regulation_ids():
    """ES 侧全集 _id"""
    r = requests.post(f"{ES}/t_regulation/_search?scroll={SCROLL_TTL}",
                      json={"size": BATCH, "_source": False}, timeout=15)
    p = r.json(); sid = p["_scroll_id"]
    ids = set(h["_id"] for h in p["hits"]["hits"])
    while True:
        r2 = requests.post(f"{ES}/_search/scroll", json={"scroll": SCROLL_TTL, "scroll_id": sid}, timeout=15)
        p2 = r2.json()
        if not p2["hits"]["hits"]:
            break
        ids.update(h["_id"] for h in p2["hits"]["hits"])
    requests.delete(f"{ES}/_search/scroll", json={"scroll_id": [sid]}, timeout=5)
    return ids


def scroll_missing_docs(missing):
    """只产出差集对应的文档（生成器）"""
    r = requests.post(f"{ES}/t_regulation/_search?scroll={SCROLL_TTL}",
                      json={"size": BATCH, "_source": True, "query": {"ids": {"values": list(missing)}}}, timeout=15)
    p = r.json(); sid = p["_scroll_id"]

    def _batch(hits):
        out = []
        for h in hits:
            s = h["_source"]
            text_val = s.get("fullText") or ""
            if not text_val:
                print(f"  [SKIP] {h['_id']} fullText 为空，无法向量化")
                continue
            domains = s.get("complianceDomain") or []
            out.append({
                "id": h["_id"],
                "text": text_val,
                "esId": h["_id"],
                "name": s.get("name", ""),
                "dimension": s.get("dimension", ""),
                "industry": ",".join(domains) if isinstance(domains, list) else str(domains),
                "region": s.get("region", ""),
            })
        return out

    try:
        batch = _batch(p["hits"]["hits"])
        if batch:
            yield batch
        while True:
            r2 = requests.post(f"{ES}/_search/scroll", json={"scroll": SCROLL_TTL, "scroll_id": sid}, timeout=15)
            p2 = r2.json()
            if not p2["hits"]["hits"]:
                break
            batch = _batch(p2["hits"]["hits"])
            if batch:
                yield batch
    finally:
        requests.delete(f"{ES}/_search/scroll", json={"scroll_id": [sid]}, timeout=5)


def milvus_es_ids():
    """Milvus 侧 es_id 全集（query 分页拉取）"""
    from pymilvus import connections, Collection
    connections.connect(host="127.0.0.1", port="19530")
    c = Collection("regulation_vectors")
    c.load()
    ids = set()
    offset = 0
    while True:
        rows = c.query(expr='es_id != ""', output_fields=["es_id"], offset=offset, limit=200)
        if not rows:
            break
        ids.update(r["es_id"] for r in rows)
        offset += 200
        if len(rows) < 200:
            break
    return ids


def main():
    es_ids = scroll_regulation_ids()
    print(f"ES t_regulation 全集: {len(es_ids)}")
    mv_ids = milvus_es_ids()
    print(f"Milvus regulation_vectors es_id 全集: {len(mv_ids)}")
    missing = es_ids - mv_ids
    print(f"差集（ES 有 Milvus 缺）: {len(missing)} 条")
    if not missing:
        print("无缺口，无需补数")
        return

    pushed = 0
    for data_list in scroll_missing_docs(missing):
        payload = {"collectionName": "regulation_vectors", "data": data_list}
        r = requests.post(BATCH_STORE, headers=HEADERS, json=payload, timeout=300)
        if r.status_code == 200 and r.json().get("code") in (200, 0):
            pushed += len(data_list)
            print(f"  已推送 {pushed}/{len(missing)}")
        else:
            print(f"  批次失败({r.status_code}): {r.text[:200]}")
            raise SystemExit(1)

    # 复核
    mv_after = milvus_es_ids()
    still = es_ids - mv_after
    print(f"补数后 Milvus 全集: {len(mv_after)}；剩余缺口: {len(still)}")
    if still:
        print(f"[WARN] 仍缺: {list(still)[:5]}")
        raise SystemExit(1)
    print("[DONE] 差集补齐")


if __name__ == "__main__":
    main()
