# -*- coding: utf-8 -*-
"""t_risk createdAt 回填：以 PG 评估时间为权威源。

迁移窗口内 create_at 是 Jackson 数组形态无法转 date，曾以迁移时刻近似写入。
本脚本按 assessmentId 关联 PG t_assessment_result.created_at，逐评估
update_by_query 写回真实时间。孤儿文档（评估已删）应先另行处理。
"""
import io
import sys
import json
import requests

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ES = "http://127.0.0.1:9200"
# PG 查询结果（docker exec 导出，见执行记录）：id -> created_at（UTC，ES date 兼容 ISO 格式）
PG_CREATED_AT = {
    2:  "2026-04-07T03:17:14.494Z",
    3:  "2026-04-07T03:27:43.998Z",
    4:  "2026-04-07T03:37:00.899Z",
    5:  "2026-04-07T03:46:29.481Z",
    6:  "2026-04-07T04:55:35.031Z",
    7:  "2026-04-07T05:05:19.083Z",
    8:  "2026-04-07T05:11:20.197Z",
    9:  "2026-04-07T05:18:03.669Z",
    10: "2026-04-07T05:19:31.417Z",
    11: "2026-04-07T05:23:36.583Z",
    12: "2026-04-07T06:04:13.551Z",
    13: "2026-04-07T06:11:45.576Z",
    14: "2026-04-07T06:16:37.582Z",
    15: "2026-04-07T06:31:19.974Z",
    16: "2026-04-09T05:32:39.123Z",
    17: "2026-08-11T15:01:57.450Z",
    21: "2026-08-26T14:00:26.820Z",
    22: "2026-08-26T14:40:19.387Z",
    23: "2026-08-26T14:45:26.753Z",
    24: "2026-08-27T06:21:50.794Z",
    25: "2026-08-27T06:37:09.124Z",
    26: "2026-08-27T06:43:17.602Z",
    32: "2026-08-27T07:35:51.349Z",
    33: "2026-08-27T07:36:24.724Z",
    34: "2026-08-27T07:36:46.975Z",
}


def main():
    # ES 侧实际存在的 assessmentId 桶（回填前实测）
    r = requests.post(f"{ES}/t_risk/_search", json={
        "size": 0, "aggs": {"by_assessment": {"terms": {"field": "assessmentId", "size": 50}}}
    }, timeout=15)
    buckets = r.json()["aggregations"]["by_assessment"]["buckets"]
    print(f"t_risk 现有评估桶: {len(buckets)} 个")

    total_updated = 0
    for b in buckets:
        aid = b["key"]
        if aid not in PG_CREATED_AT:
            print(f"  [SKIP] assessmentId={aid} 在 PG 无对应评估（{b['doc_count']} 条未回填）")
            continue
        ts = PG_CREATED_AT[aid]
        r = requests.post(f"{ES}/t_risk/_update_by_query?refresh=true&wait_for_completion=true", json={
            "query": {"term": {"assessmentId": aid}},
            "script": {
                "source": 'ctx._source.createdAt = params.ts',
                "lang": "painless",
                "params": {"ts": ts},
            },
        }, timeout=120)
        j = r.json()
        if j.get("failures"):
            raise RuntimeError(f"assessmentId={aid} 回填失败: {str(j['failures'][:1])[:200]}")
        total_updated += j.get("updated", 0)
        print(f"  assessmentId={aid}: {j.get('updated')} 条 <- {ts}")

    print(f"\n共回填 {total_updated} 条")

    # 验证：createdAt min/max 应落在 2026-04-07 ~ 2026-08-27
    r = requests.post(f"{ES}/t_risk/_search", json={
        "size": 0, "aggs": {
            "min_ts": {"min": {"field": "createdAt"}},
            "max_ts": {"max": {"field": "createdAt"}},
        }
    }, timeout=15)
    aggs = r.json()["aggregations"]
    print(f"createdAt min = {aggs['min_ts']['value_as_string']}")
    print(f"createdAt max = {aggs['max_ts']['value_as_string']}")

    c = requests.get(f"{ES}/t_risk/_count", timeout=15).json()["count"]
    print(f"t_risk 总量 = {c}")


if __name__ == "__main__":
    main()
