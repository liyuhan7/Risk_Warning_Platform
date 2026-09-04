# -*- coding: utf-8 -*-
"""迁移窗口步骤 3-4：ES 四索引 camelCase 迁移（双段同名删建方案）。

流程（对 t_risk / t_behavior / t_regulation 三索引；t_indicator 已是 camelCase 但
须补 complianceDomain 回填，同样走改名段）：
  段一（改名）：{idx} -> {idx}_v2，painlessly script 改字段名 + 值修正
  段二（回迁）：删原索引 -> 按 es_mappings.json 同名重建 -> 从 _v2 回灌 -> 删 _v2

回滚：段一完成后未删原索引前，直接删 _v2 即可；段二删原索引后如失败，
须从快照 pre_camelcase_migration restore。
"""
import json
import sys
import io
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import requests

ES = "http://127.0.0.1:9200"

# 各索引的改名映射（snake_case -> camelCase）
RENAME = {
    "t_risk": {
        "create_at": "createdAt",
        "risk_level": "riskLevel",
        "project_id": "projectId",
        "assessment_id": "assessmentId",
        "affected_objects": "affectedObjects",
        "impact_scope": "impactScope",
        "responsible_party": "responsibleParty",
        "related_indicators": "relatedIndicators",
        "processing_status": "processingStatus",
        "countermeasures": "countermeasures",
        "detectability": "detectability",
    },
    "t_behavior": {
        "description_vector": "descriptionVector",
        "created_at": "createdAt",
        "project_id": "projectId",
        "behavior_date": "behaviorDate",
        "quantitative_data": "quantitativeData",
    },
    "t_regulation": {
        "full_text_vector": "fullTextVector",
        "full_text": "fullText",
        "created_at": "createdAt",
        "applicable_subject": "applicableSubject",
        "quantitative_indicator": "quantitativeIndicator",
    },
    "t_indicator": {
        # 向量字段由 @JsonProperty 以 snake_case 写入，业务字段已 camelCase
        "name_vector": "nameVector",
    },
}

# complianceDomain 回填：industry 数组整体平移为同名字段
COMPLIANCE_DOMAIN = {
    "t_indicator": "industry",
    "t_regulation": "industry",
}

MAPPINGS_FILE = "documents/es_mappings.json"


def req(method, path, ok=(200, 201), body=None):
    r = requests.request(method, ES + path, json=body, timeout=120)
    if r.status_code not in ok:
        raise RuntimeError(f"{method} {path} -> {r.status_code}: {r.text[:300]}")
    return r


def rename_stage(index):
    """段一：reindex 到 _v2 并逐字段改名/回填"""
    ren = RENAME.get(index, {})
    src_field = COMPLIANCE_DOMAIN.get(index)
    ops = []
    if index == "t_risk":
        # F-09：create_at 是 Jackson 序列化 LocalDateTime 的数组形态，无法直接转 date；
        # createdAt 以迁移时刻近似写入（半开窗精度损失，记录于窗口报告）
        ops.append('ctx._source.remove("create_at")')
        ops.append('ctx._source.createdAt = new Date()')
    for old, new in ren.items():
        if index == "t_risk" and old == "create_at":
            continue  # 已在上面单独处理
        # 幂等：目标字段已存在则跳过（脚本可重入）
        ops.append(f'if (ctx._source.{new} == null) {{ ctx._source.{new} = ctx._source.remove("{old}") }}')
    if src_field:
        # 回填：源字段改名为 complianceDomain（有值才平移，避免 null 覆盖已有值）
        ops.append(f'if (ctx._source.{src_field} != null) {{ ctx._source.complianceDomain = ctx._source.remove("{src_field}") }}')

    # painless 多语句：普通语句以分号结尾，if 块后不加（} 后接 ; 会触发解析歧义）
    parts = []
    for i, s in enumerate(ops):
        if s.startswith("if "):
            parts.append(s + (" " if i < len(ops) - 1 else ""))
        else:
            parts.append(s + ";")
    script = "".join(parts)
    # noop 分支无字段读写，直接跳过 reindex 保持源不动
    if not ops:
        print(f"  段一 {index}: 无改名项，跳过 reindex（保持源）")
        return 0
    body = {
        "source": {"index": index},
        "dest": {"index": index + "_v2"},
        "script": {"source": script, "lang": "painless"},
    }
    r = requests.post(ES + "/_reindex?refresh=true&wait_for_completion=true", json=body, timeout=600)
    if r.status_code not in (200, 201):
        raise RuntimeError(f"reindex {index} -> {index}_v2 失败: {r.text[:300]}")
    j = r.json()
    if j.get("failures"):
        raise RuntimeError(f"reindex {index} 存在失败项: {str(j['failures'][:2])[:300]}")
    print(f"  段一 {index} -> {index}_v2: created={j.get('created')} updated={j.get('updated')}")
    return j.get("created", 0) + j.get("updated", 0)


def verify_stage(index, expected):
    """段一核对：_v2 条数与关键字段覆盖"""
    c = req("GET", f"/{index}_v2/_count").json()["count"]
    if c != expected:
        raise RuntimeError(f"{index}_v2 条数 {c} != 预期 {expected}")
    for f in RENAME.get(index, {}).values():
        n = req("POST", f"/{index}_v2/_count", body={"query": {"exists": {"field": f}}}).json()["count"]
        print(f"  {index}_v2.{f} = {n}")
    if index in COMPLIANCE_DOMAIN:
        n = req("POST", f"/{index}_v2/_count", body={"query": {"exists": {"field": "complianceDomain"}}}).json()["count"]
        print(f"  {index}_v2.complianceDomain = {n}")


def rebuild_stage(index):
    """段二：删原索引、按 es_mappings.json 同名重建"""
    m = json.load(open(MAPPINGS_FILE, encoding="utf-8"))
    req("DELETE", f"/{index}", ok=(200, 404))
    r = requests.put(ES + "/" + index, json=m[index], timeout=60)
    if r.status_code not in (200, 201):
        raise RuntimeError(f"重建 {index} 失败: {r.text[:300]}")
    print(f"  段二 {index} 已按 es_mappings.json 同名重建")


def backfill_stage(index):
    """段二回灌：_v2 -> 原名"""
    body = {"source": {"index": index + "_v2"}, "dest": {"index": index}}
    r = requests.post(ES + "/_reindex?refresh=true&wait_for_completion=true", json=body, timeout=600)
    if r.status_code not in (200, 201):
        raise RuntimeError(f"回灌 {index} 失败: {r.text[:300]}")
    j = r.json()
    if j.get("failures"):
        raise RuntimeError(f"回灌 {index} 存在失败项: {str(j['failures'][:2])[:300]}")
    print(f"  回灌 {index}: {j.get('created')}")


def drop_v2(index):
    req("DELETE", f"/{index}_v2", ok=(200, 404))
    print(f"  清理 {index}_v2")


BASELINE = {"t_risk": 2596, "t_behavior": 2146, "t_regulation": 4865, "t_indicator": 1144}

if __name__ == "__main__":
    only = sys.argv[1:] or list(BASELINE)
    for index in only:
        expected = BASELINE[index]
        print(f">>> {index}（预期 {expected} 条）")
        rename_stage(index)
        verify_stage(index, expected)
        rebuild_stage(index)
        backfill_stage(index)
        final = req("GET", f"/{index}/_count").json()["count"]
        if final != expected:
            raise RuntimeError(f"{index} 终态条数 {final} != 预期 {expected}")
        drop_v2(index)
        print(f"<<< {index} 完成: {final} 条\n")
    print("[DONE] 四索引迁移完成")
