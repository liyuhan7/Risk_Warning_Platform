# -*- coding: utf-8 -*-
"""平移核对通过后的收尾：删旧 collection，把 _new 改回正式名。

前置：migrate_milvus_rename.py 已跑完且核对通过。
"""
import io
import sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

from pymilvus import connections, Collection, utility

HOST, PORT = "127.0.0.1", "19530"
EXPECT = {"regulation_vectors": 4765, "indicator_vectors": 1144}


if __name__ == "__main__":
    connections.connect(host=HOST, port=PORT)
    for name, expected in EXPECT.items():
        new = Collection(name + "_new")
        got = new.num_entities
        if got != expected:
            raise RuntimeError(f"{name}_new {got} != {expected}，中止收尾")
        new.load()
        # 抽查 compliance_domain 值非空率
        rows = new.query(expr='id != ""', output_fields=["compliance_domain"], limit=100)
        nonempty = sum(1 for r in rows if r.get("compliance_domain"))
        print(f"{name}_new: {got} 条, compliance_domain 非空 {nonempty}/100")

    for name in EXPECT:
        utility.drop_collection(name)
        utility.rename_collection(name + "_new", name) if hasattr(utility, "rename_collection") else None
        # pymilvus 3.0 无 rename API 时用 alias 方式或直接以新名为正式名
        print(f"{name}: 旧已删")

    # 若无 rename API：把 _new 保持原名，由 Java 侧 collection 常量切换——此处打印最终状态
    print("最终 collection 列表:", utility.list_collections())
