# -*- coding: utf-8 -*-
"""迁移窗口步骤 5：Milvus collection 改名平移（industry -> compliance_domain）。

对两个 collection 各自：新建含 compliance_domain 字段的 schema -> 从旧
collection 逐批 query 出全部行（含向量）-> insert 到新 collection -> 建索引
-> load -> 核对条数 -> 删旧 collection。

向量原样平移，不经 bert 重算。ID 沿用旧值，保证 es_id 关联不断。
"""
import io
import sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

from pymilvus import (
    connections, Collection, CollectionSchema, FieldSchema, utility, Index,
)
import time

HOST, PORT = "127.0.0.1", "19530"
DIM = 768

COLLECTIONS = {
    "regulation_vectors": 4765,   # 旧基线（缺 100 条，补数由 sync_to_milvus.py 负责）
    "indicator_vectors": 1144,
}


def build_schema():
    # dtype 21 = VarChar（pymilvus 3.x 枚举值，与旧 schema 一致），101 = FloatVector
    return [
        FieldSchema(name="id", dtype=21, max_length=64, is_primary=True),
        FieldSchema(name="vector", dtype=101, dim=DIM),
        FieldSchema(name="es_id", dtype=21, max_length=64),
        FieldSchema(name="name", dtype=21, max_length=256),
        FieldSchema(name="dimension", dtype=21, max_length=64),
        FieldSchema(name="compliance_domain", dtype=21, max_length=256),
        FieldSchema(name="region", dtype=21, max_length=64),
    ]


def transfer(old_name, expected_old):
    new_name = old_name + "_new"
    if utility.has_collection(new_name):
        print(f"  清理残留 {new_name}")
        utility.drop_collection(new_name)

    old = Collection(old_name)
    # 旧 collection 若无索引则先建（query 需 load，load 需索引）
    try:
        old.load()
    except Exception:
        print(f"  旧 {old_name} 缺索引，先建 IVF_FLAT/COSINE 再 load")
        Index(old, "vector", {"index_type": "IVF_FLAT", "metric_type": "COSINE", "params": {"nlist": 1024}})
        old.load()

    schema = CollectionSchema(build_schema(), description="compliance_domain 版: " + old_name)
    new = Collection(new_name, schema=schema, shards_num=2)

    # 旧 industry 字段名（改名前）
    out_fields = ["id", "vector", "es_id", "name", "dimension", "industry", "region"]
    # 分批 query（以主键序遍历）
    total = old.num_entities
    print(f"  旧 {old_name}: {total} 条（预期 {expected_old}）")
    if total != expected_old:
        raise RuntimeError(f"{old_name} 条数 {total} != 基线 {expected_old}")

    # query 表达式用主键范围分段（VarChar 主键不支持范围，改用 offset 分页 query）
    batch = 200
    moved = 0
    offset = 0
    while True:
        rows = old.query(expr='id != ""', output_fields=out_fields, offset=offset, limit=batch)
        if not rows:
            break
        ins = [
            {
                "id": r["id"],
                "vector": r["vector"],
                "es_id": r.get("es_id", ""),
                "name": r.get("name", ""),
                "dimension": r.get("dimension", ""),
                "compliance_domain": r.get("industry", ""),
                "region": r.get("region", ""),
            }
            for r in rows
        ]
        new.insert(ins)
        moved += len(rows)
        offset += batch
        if len(rows) < batch:
            break
    new.flush()
    print(f"  平移 {moved} 条到 {new_name}")

    # 建索引 + load
    Index(new, "vector", {"index_type": "IVF_FLAT", "metric_type": "COSINE", "params": {"nlist": 1024}})
    # 等索引就绪
    for _ in range(60):
        idx_progress = new.indexes[0].params if new.indexes else None
        try:
            new.load()
            break
        except Exception:
            time.sleep(2)
    new.load()

    # 核对
    got = new.num_entities
    print(f"  新 {new_name}: {got} 条")
    if got != moved:
        raise RuntimeError(f"{new_name} 条数 {got} != 平移数 {moved}")
    return moved


if __name__ == "__main__":
    connections.connect(host=HOST, port=PORT)
    total_moved = {}
    for name, expected in COLLECTIONS.items():
        print(f">>> {name}")
        total_moved[name] = transfer(name, expected)
    print("[DONE] 平移完成:", total_moved)
    print("确认无误后执行删除旧 collection 与重命名（下一步脚本）。")
