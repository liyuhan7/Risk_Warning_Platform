import requests
import json
import time

# 配置参数
BASE_URL = "http://localhost:8088/api/knowledge/test/vectorization/batch-store"
ES_URL = "http://localhost:9200"
TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJmdWxsTmFtZSI6Iua1i-ivleeUqOaItyIsInVzZXJJZCI6MiwiZW1haWwiOiJ0ZXN0QGV4YW1wbGUuY29tIiwic3ViIjoidGVzdEBleGFtcGxlLmNvbSIsImlhdCI6MTc3NTQ4NDkzOCwiZXhwIjoxNzc1NTcxMzM4fQ.pmfEJeztFxDCI0HJa-bCJKyt4TPd9fJDkA_qa74C6Vo"
BATCH_SIZE = 50
SCROLL_TTL = "2m"

HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Content-Type": "application/json"
}


def scroll_all_ids(es_index):
    """scroll 拉取 ES 某索引全部 _id（不依赖 from/size，写入并发下不漏不重）"""
    ids = []
    res = requests.post(
        f"{ES_URL}/{es_index}/_search?scroll={SCROLL_TTL}",
        json={"size": BATCH_SIZE, "_source": False},
        timeout=10,
    )
    res.raise_for_status()
    payload = res.json()
    scroll_id = payload.get("_scroll_id")
    ids.extend(h["_id"] for h in payload["hits"]["hits"])

    while True:
        res = requests.post(
            f"{ES_URL}/_search/scroll",
            json={"scroll": SCROLL_TTL, "scroll_id": scroll_id},
            timeout=10,
        )
        res.raise_for_status()
        payload = res.json()
        hits = payload["hits"]["hits"]
        if not hits:
            break
        ids.extend(h["_id"] for h in hits)
        if len(hits) < BATCH_SIZE:
            break

    # 主动清理 scroll 上下文
    if scroll_id:
        requests.delete(f"{ES_URL}/_search/scroll", json={"scroll_id": [scroll_id]}, timeout=5)
    return set(ids)


def scroll_docs(es_index, text_field):
    """scroll 逐批产出待向量化文档（生成器），文本为空的跳过"""
    res = requests.post(
        f"{ES_URL}/{es_index}/_search?scroll={SCROLL_TTL}",
        json={"size": BATCH_SIZE, "_source": True},
        timeout=10,
    )
    res.raise_for_status()
    payload = res.json()
    scroll_id = payload.get("_scroll_id")

    def _batch(hits):
        data_list = []
        for hit in hits:
            source = hit["_source"]
            text_val = source.get(text_field, "")
            if not text_val:
                continue
            # ES 字段已改名 complianceDomain（P0-11 决议 3.2），兼容迁移期间的旧字段名
            domains = source.get("complianceDomain") or source.get("industry") or []
            domain_str = ",".join(domains) if isinstance(domains, list) else str(domains)
            data_list.append({
                "id": hit["_id"],
                "text": text_val,
                "esId": hit["_id"],
                "name": source.get("name", ""),
                "dimension": source.get("dimension", ""),
                "industry": domain_str,
                "region": source.get("region", ""),
            })
        return data_list

    try:
        batch = _batch(payload["hits"]["hits"])
        if batch:
            yield batch

        while True:
            res = requests.post(
                f"{ES_URL}/_search/scroll",
                json={"scroll": SCROLL_TTL, "scroll_id": scroll_id},
                timeout=10,
            )
            res.raise_for_status()
            payload = res.json()
            hits = payload["hits"]["hits"]
            if not hits:
                break
            batch = _batch(hits)
            if batch:
                yield batch
            if len(hits) < BATCH_SIZE:
                break
    finally:
        if scroll_id:
            requests.delete(f"{ES_URL}/_search/scroll", json={"scroll_id": [scroll_id]}, timeout=5)


def verify_sync(es_index, milvus_collection, es_ids):
    """同步后校验：调 batch-store 侧查询接口不可行，改为对比 ES id 与
    Milvus collection 的 es_id 全集。此步经 8088 服务暴露的检索接口或
    迁移窗口探针执行；此处先输出期望值供核对。"""
    print(f"[VERIFY] {es_index} -> {milvus_collection}: ES 侧 {len(es_ids)} 条")
    return len(es_ids)


def sync_index(es_index, milvus_collection, text_field):
    print(f"\n>>> 开始同步索引: {es_index} -> {milvus_collection}")

    es_ids = scroll_all_ids(es_index)
    print(f"总数据量(scroll 计数): {len(es_ids)}")

    stored = 0
    for data_list in scroll_docs(es_index, text_field):
        payload = {
            "collectionName": milvus_collection,
            "data": data_list
        }
        try:
            store_res = requests.post(BASE_URL, headers=HEADERS, json=payload)
            if store_res.status_code == 200:
                stored += len(data_list)
                print(f"进度: 已提交 {stored} 条", end='\r')
            else:
                print(f"\n批次失败({store_res.status_code}): {store_res.text[:200]}")
        except Exception as e:
            print(f"\n批次异常: {str(e)}")

        # 稍微避让 BERT 服务，防止其 CPU 过载
        time.sleep(0.5)

    print(f"\n<<< 索引 {es_index} 同步结束: 提交 {stored} 条, ES 全集 {len(es_ids)} 条")
    if stored != len(es_ids):
        print(f"[WARN] 提交数与 ES 全集不一致，差 {len(es_ids) - stored} 条（可能为 text 为空的跳过项，须逐项核对）")
    return es_ids


if __name__ == "__main__":
    # 执行同步（scroll 版：修复 from/size 深分页在写入并发下漏条的问题）
    # 1. 监管法规
    es_ids_reg = sync_index("t_regulation", "regulation_vectors", "full_text")
    # 2. 预警指标
    es_ids_ind = sync_index("t_indicator", "indicator_vectors", "name")
    print("\n[FINISH] 两库同步完成。核对基准：")
    print(f"  regulation: ES={len(es_ids_reg)}, Milvus 应为补齐后的 {len(es_ids_reg)}")
    print(f"  indicator:  ES={len(es_ids_ind)}, Milvus 应为 {len(es_ids_ind)}")
