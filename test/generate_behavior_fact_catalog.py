"""从审核规则生成事实目录，语义别名为发布阶段的显式登记，不在运行时模糊匹配。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "risk-warning-processing/src/main/resources/rules/labor-reviewed-test-v2.json"


def leaves(node, inherited):
    for name in ("allOf", "anyOf"):
        for child in node.get(name) or []:
            yield from leaves(child, inherited)
    if node.get("notOf"):
        yield from leaves(node["notOf"], inherited)
    if node.get("conditionId"):
        yield node, node.get("valueType") or inherited


def main():
    registry = json.loads(RULES.read_text(encoding="utf-8"))
    definitions, bindings, known = {}, [], {}
    # 两个阈值比较引用同一人员同一天的观测值，阈值不属于事实本身。
    aliases = {
        "req-22ea9d48093c36751d80b146-daily-hours": "employee-daily-overtime-hours",
        "req-22ea9d48093c36751d80b146-ordinary-daily-hours": "employee-daily-overtime-hours",
    }
    for indicator, rule in sorted(registry["rules"].items()):
        for req in rule["requirements"]:
            items = [(None, "APPLICABILITY", None, req["applicabilityGate"]["text"])]
            for node, kind in list(leaves(req["condition"], req["valueType"])) + (
                list(leaves(req["exceptWhen"], req["valueType"])) if req.get("exceptWhen") else []
            ):
                text = node["promptHint"]
                if node["conditionId"] in aliases:
                    text = "本次人员当日延长工作时间；所有引用必须是同一人员同一天，不含正常工作时间。"
                items.append((node["conditionId"], kind, node.get("unit"), text))
            for condition_id, kind, unit, text in items:
                signature = (kind, unit, text)
                key = aliases.get(condition_id) or known.get(signature)
                if key is None:
                    key = "fact-" + hashlib.sha256(json.dumps(signature, ensure_ascii=False).encode()).hexdigest()[:16]
                known[signature] = key
                definitions[key] = {
                    "type": kind, "unit": unit, "text": text,
                    "scope": "仅针对本次行为及关联原文中的同一主体、对象和期间；人员、期间或口径不清时返回未知，不混用其他记录。",
                }
                bindings.append({"indicatorId": indicator, "requirementId": req["requirementId"],
                                 "conditionId": condition_id, "factKey": key})
    catalog = {"catalogVersion": "labor-facts-2026.10-v1", "registryVersion": registry["registryVersion"],
               "rulesHash": hashlib.sha256(RULES.read_text(encoding="utf-8").encode("utf-8")).hexdigest(),
               "definitions": dict(sorted(definitions.items())), "bindings": bindings}
    target = RULES.parent / "labor-fact-bindings-v1.json"
    target.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = ["# 共享事实绑定核对清单", "", "语义等价的原子问题在发布阶段合并；适用门独立绑定。数值阈值、权重及评分规则保持不变。", "",
            "|共享事实|类型／单位|问题|绑定位置数|", "|---|---|---|---:|"]
    for key, value in catalog["definitions"].items():
        count = sum(b["factKey"] == key for b in bindings)
        rows.append(f"|`{key}`|{value['type']} / {value['unit'] or '-'}|{value['text'].replace('|', '／')}|{count}|")
    rows += ["", "## 条件与适用门绑定", "", "|指标|要求|条件（空为适用门）|共享事实|", "|---|---|---|---|"]
    rows += [f"|`{b['indicatorId']}`|`{b['requirementId']}`|`{b['conditionId'] or '适用门'}`|`{b['factKey']}`|" for b in bindings]
    (ROOT / "documents/behavior-fact-bindings.md").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"indicators={len(registry['rules'])} bindings={len(bindings)} definitions={len(definitions)}")


if __name__ == "__main__":
    main()
