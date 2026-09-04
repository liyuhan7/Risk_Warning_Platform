# -*- coding: utf-8 -*-
"""ES 四索引字段覆盖探针。

迁移窗口各步执行后的核对工具：逐索引统计关键字段的非空覆盖数，
不接受 HTTP 200 作为验证依据，输出实测覆盖值供与基准比对。
基准（2026-08 实测，见 documents/plan0/baseline/p0-09-field-contract.md）：
  t_risk 2596 / t_regulation 4865 / t_indicator 1144 / t_behavior 2146
迁移后期望：
  向量字段 coverage 与迁移前一致（descriptionVector/nameVector/fullTextVector）
  complianceDomain 在 t_indicator/t_regulation 均 100% 覆盖（回填 6009 条）
  createdAt 在 t_risk 2596 条全覆盖（F-09 修正）
"""
import io
import sys

# Windows GBK 控制台下强制 UTF-8 输出，避免中文列名乱码
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import requests

ES_URL = "http://127.0.0.1:9200"

# 每索引需要核对覆盖数的字段
PROBE_FIELDS = {
    "t_risk": ["createdAt", "relatedIndicators"],
    "t_behavior": ["descriptionVector", "createdAt", "projectId"],
    "t_regulation": ["fullTextVector", "industry", "complianceDomain", "createdAt", "fullText"],
    "t_indicator": ["nameVector", "industry", "complianceDomain", "createdAt"],
}

# 迁移后不应再出现的 snake_case 残留字段
LEGACY_FIELDS = {
    "t_risk": ["create_at", "related_indicators", "risk_level", "project_id"],
    "t_behavior": ["description_vector", "created_at", "project_id", "behavior_date"],
    "t_regulation": ["full_text_vector", "full_text", "created_at", "applicable_subject"],
    "t_indicator": ["name_vector", "created_at"],
}


def doc_count(index):
    r = requests.get(f"{ES_URL}/{index}/_count", timeout=10)
    r.raise_for_status()
    return r.json()["count"]


def coverage(index, field):
    """exists 查询统计字段非空覆盖数；返回 -1 表示字段不存在"""
    r = requests.post(
        f"{ES_URL}/{index}/_count",
        json={"query": {"exists": {"field": field}}},
        timeout=10,
    )
    if r.status_code != 200:
        return -1
    return r.json()["count"]


def main():
    ok = True
    print(f"{'索引':<14}{'文档数':>8}  核对字段覆盖")
    for index, fields in PROBE_FIELDS.items():
        try:
            total = doc_count(index)
        except Exception as e:
            print(f"{index:<14}   查询失败: {e}")
            ok = False
            continue
        parts = []
        for f in fields:
            c = coverage(index, f)
            parts.append(f"{f}={c}")
        print(f"{index:<14}{total:>8}  " + "  ".join(parts))
    print()
    print("snake_case 残留检查（期望全部 =0 或 -1）:")
    for index, fields in LEGACY_FIELDS.items():
        parts = []
        for f in fields:
            c = coverage(index, f)
            if c not in (0, -1):
                ok = False
            parts.append(f"{f}={c}")
        print(f"{index:<14}  " + "  ".join(parts))
    print()
    print("结论:", "全部通过" if ok else "存在残留或覆盖异常，逐项排查")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
