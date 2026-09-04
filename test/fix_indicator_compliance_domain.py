# -*- coding: utf-8 -*-
"""t_indicator 的 complianceDomain 回填修复。

第一轮迁移时 industry 已被 remove 但因幂等保护未写入 complianceDomain
（脚本先跑了 name_vector 轮）。从快照 pre_camelcase_migration 恢复
t_indicator 原始 industry 数据，平移到当前索引的 complianceDomain 字段。

流程：快照恢复出临时索引 -> 取 (id, industry) 对照 -> update_by_query 回填。
"""
import requests

ES = "http://127.0.0.1:9200"
SNAP_REPO = "p0_backup"
SNAP_NAME = "pre_camelcase_migration"
TMP = "t_indicator_snap_restore"


def main():
    # 1. 从快照恢复 t_indicator 到临时索引（rename 方式不覆盖现有索引）
    body = {
        "indices": "t_indicator",
        "rename_pattern": "t_indicator",
        "rename_replacement": TMP,
    }
    r = requests.post(f"{ES}/_snapshot/{SNAP_REPO}/{SNAP_NAME}/_restore?wait_for_completion=true", json=body, timeout=300)
    print("restore:", r.status_code, r.text[:200])
    r.raise_for_status()

    # 2. 核对临时索引 industry 覆盖
    c = requests.get(f"{ES}/{TMP}/_count", timeout=30).json()["count"]
    ci = requests.post(f"{ES}/{TMP}/_count", json={"query": {"exists": {"field": "industry"}}}, timeout=30).json()["count"]
    print(f"临时索引 {TMP}: {c} 条, industry 覆盖 {ci}")
    assert c == 1144 and ci == 1144, "快照恢复数据不完整"

    # 3. 以 es_id 关联回填：从临时索引读 (id, industry)，对当前索引 update_by_query 逐条写
    #    update_by_query 无法跨索引取值，改走滚动读 + bulk update
    r = requests.post(f"{ES}/{TMP}/_search?scroll=2m", json={"size": 200, "_source": ["id", "industry"]}, timeout=30)
    payload = r.json()
    scroll_id = payload.get("_scroll_id")
    pairs = []
    while True:
        for h in payload["hits"]["hits"]:
            ind = h["_source"].get("industry")
            if ind:
                pairs.append((h["_source"]["id"], ind))
        r2 = requests.post(f"{ES}/_search/scroll", json={"scroll": "2m", "scroll_id": scroll_id}, timeout=30)
        payload = r2.json()
        if not payload["hits"]["hits"]:
            break
    print(f"待回填 {len(pairs)} 条")

    # 4. bulk update 到当前索引（按 _id 定位须先查当前索引 id->_id 映射；
    #    迁移时 reindex 保留 _id，故直接用快照 _id）
    bulk = ""
    for doc_id, ind in pairs:
        # 当前索引 _id 与快照一致（reindex 未指定 dest.id 改写）
        bulk += '{"update": {"_index": "t_indicator", "_id": "%s"}}\n' % doc_id
        if isinstance(ind, list):
            val = ",".join(ind)
        else:
            val = str(ind)
        bulk += '{"doc": {"complianceDomain": %s}}\n' % ('["' + '","'.join(
            [x for x in (ind if isinstance(ind, list) else [str(ind)])]) + '"]')
    r = requests.post(f"{ES}/_bulk?refresh=true", data=bulk.encode("utf-8"),
                      headers={"Content-Type": "application/x-ndjson"}, timeout=300)
    j = r.json()
    errors = [it for it in j.get("items", []) if it.get("update", {}).get("error")]
    print("bulk 状态:", r.status_code, "失败项:", len(errors))
    if errors:
        print(str(errors[:2])[:300])
        raise SystemExit(1)

    # 5. 核对终态
    cf = requests.post(f"{ES}/t_indicator/_count", json={"query": {"exists": {"field": "complianceDomain"}}}, timeout=30).json()["count"]
    print(f"t_indicator.complianceDomain 终态覆盖: {cf} / 1144")

    # 6. 删临时索引
    requests.delete(f"{ES}/{TMP}", timeout=30)
    print("临时索引已清理")


if __name__ == "__main__":
    main()
