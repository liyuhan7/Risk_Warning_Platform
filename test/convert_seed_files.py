# -*- coding: utf-8 -*-
"""种子文件转换：snake_case -> camelCase + industry -> complianceDomain。

与迁移窗口对线上索引执行的字段改名保持一致（P0-11 决议、p0-09 字段契约），
使 documents/data/ 下的种子文件与线上形态一致，init_es.py 在全新环境
初始化后 Java 可直接正确读取。

转换规则（与 test/migrate_es_camelcase.py 的 RENAME 映射同源）：
  regulation: applicable_subject->applicableSubject, full_text->fullText,
              quantitative_indicator->quantitativeIndicator, created_at->createdAt,
              industry->complianceDomain
  indicator:  industry->complianceDomain（其余字段本就 camelCase）
  behavior:   project_id->projectId, quantitative_data->quantitativeData,
              behavior_date->behaviorDate, created_at->createdAt

原文件已备份至 documents/data/backup_snake_case/。
"""
import io
import sys
import json

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

BASE = "D:/\u5927\u521b/Risk_Warning_Platform/documents/data/"

# 字段改名映射（仅顶层键，嵌套结构不动）
RENAME = {
    "regulation.json": {
        "applicable_subject": "applicableSubject",
        "full_text": "fullText",
        "quantitative_indicator": "quantitativeIndicator",
        "created_at": "createdAt",
        "industry": "complianceDomain",
    },
    "indicator.json": {
        "industry": "complianceDomain",
    },
    "behavior.json": {
        "project_id": "projectId",
        "quantitative_data": "quantitativeData",
        "behavior_date": "behaviorDate",
        "created_at": "createdAt",
    },
}


def convert(name, mapping):
    with open(BASE + name, encoding="utf-8") as f:
        items = json.load(f)
    assert isinstance(items, list), f"{name} 应为 JSON 数组"

    converted = 0
    keys_seen = set()
    for item in items:
        for old, new in mapping.items():
            if old in item:
                item[new] = item.pop(old)
                converted += 1
        keys_seen.update(item.keys())

    with open(BASE + name, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)

    # 校验：转换后不应残留任何映射源字段
    residual = set(mapping) & keys_seen
    return len(items), converted, sorted(keys_seen), residual


for name, mapping in RENAME.items():
    n, c, keys, residual = convert(name, mapping)
    print(f"{name}: {n} 条, 改名 {c} 处, 转换后字段 {keys}")
    if residual:
        raise SystemExit(f"[FAIL] {name} 仍残留 {residual}")
print("[DONE] 三文件转换完成，无残留")
