# -*- coding: utf-8 -*-
"""按 documents/es_mappings.json 重建指定索引并灌入种子数据。

安全入口（P2 阶段B迁移窗口）：
  - 只允许重建 t_indicator / t_regulation；t_behavior / t_risk 硬编码禁止，
    防止整库重建误删行为与风险运行数据；
  - 必须显式指定 --indices，缺省不重建任何索引；
  - 删除重建属于破坏性操作，运行前必须完成可恢复备份
    （documents/plan2/data/backup/pre_rebuild/）与 _id manifest 导出，
    并显式确认（--yes 跳过交互确认）；
  - 导入以种子 id 作为 ES `_id`，保证重建后 ID 不漂移，
    历史检索结果与标注引用保持有效；
  - 校验 bulk 逐条错误与导入后文档数，不接受 HTTP 200 作为验证依据。

用法：
  python test/init_es.py --indices t_indicator,t_regulation --yes
"""
import argparse
import json
import os
import sys
from urllib import error as urlerror
from urllib import request as urlrequest

ES_BASE_URL = "http://localhost:9200"

# 行为与风险数据含运行态标签与向量，不属于种子重建范围，硬编码保护
ALLOWED_INDICES = ("t_indicator", "t_regulation")

INDEX_SEED_FILES = {
    "t_indicator": "documents/data/indicator.json",
    "t_regulation": "documents/data/regulation.json",
}


def http(method, url, body=None, headers=None):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
    final_headers = dict(headers or {})
    # ES 8 对无 Content-Type 的 JSON 请求体返回 406，显式补齐默认头
    if data is not None and "Content-Type" not in final_headers:
        final_headers["Content-Type"] = "application/json"
    req = urlrequest.Request(url, data=data, headers=final_headers, method=method)
    try:
        with urlrequest.urlopen(req, timeout=300) as resp:
            payload = resp.read().decode("utf-8")
            return resp.status, payload
    except urlerror.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", errors="replace")


def load_mappings():
    with open("documents/es_mappings.json", "r", encoding="utf-8") as handle:
        mappings = json.load(handle)
    # 未安装 IK 插件的环境退回 standard 分析器，与既有行为保持一致
    mappings_str = json.dumps(mappings).replace("ik_max_word", "standard")
    return json.loads(mappings_str)


def confirm(indices):
    print("即将删除并重建索引: %s" % ", ".join(indices))
    print("该操作销毁上述索引存量数据；回滚依据 documents/plan2/data/backup/pre_rebuild/ 的全量导出。")
    print("t_behavior / t_risk 不受影响。")
    answer = input("确认请输入大写 YES: ")
    if answer != "YES":
        print("已取消，未做任何改动。")
        sys.exit(1)


def rebuild_index(index_name, mapping):
    status, text = http("DELETE", f"{ES_BASE_URL}/{index_name}")
    print(f"delete {index_name}: {status}")
    status, text = http("PUT", f"{ES_BASE_URL}/{index_name}", mapping)
    if status != 200:
        raise RuntimeError(f"创建索引 {index_name} 失败: {status} {text[:300]}")
    print(f"create {index_name}: 200")


def bulk_import(index_name, file_path):
    if not os.path.exists(file_path):
        raise RuntimeError(f"种子文件不存在: {file_path}")
    with open(file_path, "r", encoding="utf-8") as handle:
        items = json.load(handle)

    bulk_data = []
    for item in items:
        item_id = item.get("id")
        # 无 id 的行会导致重建后 ID 漂移、历史引用失效，直接失败而不是静默自动生成
        if item_id is None or str(item_id).strip() == "":
            raise RuntimeError(f"{index_name} 种子行缺少 id 字段，拒绝导入（防止 ID 漂移）")
        bulk_data.append(json.dumps(
            {"index": {"_index": index_name, "_id": str(item_id)}}, ensure_ascii=False))
        bulk_data.append(json.dumps(item, ensure_ascii=False))
    payload = ("\n".join(bulk_data) + "\n").encode("utf-8")

    print(f"bulk import {len(items)} items -> {index_name}")
    status, text = http(
        "POST", f"{ES_BASE_URL}/_bulk", body=payload,
        headers={"Content-Type": "application/x-ndjson"},
    )
    if status != 200:
        raise RuntimeError(f"bulk 请求失败: {status} {text[:300]}")
    response = json.loads(text)
    if response.get("errors"):
        failed = [
            item["index"]["_id"] if "index" in item else "?"
            for item in response["items"]
            if item.get("index", {}).get("status", 200) >= 300
        ]
        raise RuntimeError(f"bulk 存在逐条错误 {len(failed)} 条，样例: {failed[:10]}")

    # bulk 默认按 refresh_interval 异步可见，先强制刷新再计数
    status, text = http("POST", f"{ES_BASE_URL}/{index_name}/_refresh")
    if status != 200:
        raise RuntimeError(f"refresh 失败: {status} {text[:300]}")
    status, text = http("GET", f"{ES_BASE_URL}/{index_name}/_count")
    count = json.loads(text)["count"]
    if count != len(items):
        raise RuntimeError(f"{index_name} 导入后文档数 {count} != 种子行数 {len(items)}")
    print(f"{index_name}: 导入 {count} 条，文档数与种子一致")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--indices",
        default="",
        help="逗号分隔的目标索引，仅允许: %s" % ", ".join(ALLOWED_INDICES),
    )
    parser.add_argument("--yes", action="store_true", help="跳过交互确认")
    args = parser.parse_args()

    if not args.indices:
        print("未指定 --indices，缺省不重建任何索引。")
        print("可选目标: %s" % ", ".join(ALLOWED_INDICES))
        return 1

    indices = [name.strip() for name in args.indices.split(",") if name.strip()]
    forbidden = [name for name in indices if name not in ALLOWED_INDICES]
    if forbidden:
        print("拒绝重建被保护索引: %s（仅允许 %s）" % (", ".join(forbidden), ", ".join(ALLOWED_INDICES)))
        return 1
    if not indices:
        print("--indices 为空。")
        return 1

    if not args.yes:
        confirm(indices)

    mappings = load_mappings()
    for index_name in indices:
        rebuild_index(index_name, mappings[index_name])
        bulk_import(index_name, INDEX_SEED_FILES[index_name])
    print("指定索引重建与导入完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
