"""P0-04 Indicator 规则统计。

只读脚本，不修改 ES 与任何数据文件。默认以 ES 索引 t_indicator 为权威数据源，
并与导入源文件 documents/data/indicator.json 比对，暴露导入链路的丢失或漂移。

可执行性判定口径来自规则对象定义：
  CalculationRule  ruleType 与子对象类型必须匹配（RuleTypeEnum: BINARY / RANGE）
  BinaryRule       condition / trueScore / falseScore 三者齐备
  RangeRule        minValue / maxValue / calculationMethod 齐备，且存在可用评分字段
  StaticThreshold  operator / thresholdValue / riskLevel 齐备

字段读取一律按 camelCase。P0-02 已实测 t_indicator 文档为 camelCase，
snake_case 反查命中 0 条；此处不做兼容回退，以免掩盖命名不一致问题。

用法：
    py -3.9 indicator_rule_stats.py                 # ES 为源，附带 JSON 比对
    py -3.9 indicator_rule_stats.py --source json   # 仅用 JSON，ES 不可用时降级
    py -3.9 indicator_rule_stats.py --dump-samples  # 追加输出抽样明细
"""

import argparse
import json
import os
import sys
from collections import Counter

ES_URL = os.environ.get("ES_URL", "http://localhost:9200")
INDEX = "t_indicator"
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
JSON_PATH = os.path.join(REPO_ROOT, "documents", "data", "indicator.json")

# 每类抽样条数，对应计划 0 测试方式"人工抽查每类至少 5 条原始规则"
SAMPLE_SIZE = 5


def fetch_from_es():
    """用 scroll 拉全量文档，避免 10000 上限与深分页问题。"""
    try:
        from urllib.request import urlopen, Request
    except ImportError:  # pragma: no cover
        sys.exit("需要 Python 3")

    def post(path, payload):
        req = Request(
            ES_URL + path,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))

    body = {"size": 1000, "query": {"match_all": {}}}
    page = post("/%s/_search?scroll=2m" % INDEX, body)
    scroll_id = page.get("_scroll_id")
    hits = page["hits"]["hits"]
    docs = [h["_source"] for h in hits]

    while hits:
        page = post("/_search/scroll", {"scroll": "2m", "scroll_id": scroll_id})
        scroll_id = page.get("_scroll_id")
        hits = page["hits"]["hits"]
        docs.extend(h["_source"] for h in hits)

    return docs


def load_from_json():
    with open(JSON_PATH, "r", encoding="utf-8") as fh:
        return json.load(fh)


def is_blank(value):
    """空值判定：None、空串、纯空白、空集合一律视为缺失。0.0 不算缺失。"""
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip() == ""
    if isinstance(value, (list, dict)):
        return len(value) == 0
    return False


def classify_calculation_rule(doc):
    """返回 (状态, 原因)。状态取值：MISSING / EXECUTABLE / NEEDS_FIX。"""
    rule = doc.get("calculationRule")
    if is_blank(rule):
        return "MISSING", "calculationRule 为空"

    rule_type = rule.get("ruleType")
    binary = rule.get("binaryRule")
    rng = rule.get("rangeRule")

    if is_blank(rule_type):
        return "NEEDS_FIX", "ruleType 为空"

    rule_type = str(rule_type).upper()

    if rule_type == "BINARY":
        if is_blank(binary):
            return "NEEDS_FIX", "ruleType=BINARY 但 binaryRule 为空"
        missing = [
            f for f in ("condition", "trueScore", "falseScore")
            if is_blank(binary.get(f))
        ]
        if missing:
            return "NEEDS_FIX", "binaryRule 缺字段: " + ",".join(missing)
        return "EXECUTABLE", ""

    if rule_type == "RANGE":
        if is_blank(rng):
            return "NEEDS_FIX", "ruleType=RANGE 但 rangeRule 为空"
        missing = [
            f for f in ("minValue", "maxValue", "calculationMethod")
            if is_blank(rng.get(f))
        ]
        if missing:
            return "NEEDS_FIX", "rangeRule 缺字段: " + ",".join(missing)
        # maxScore 与 outOfMinScore/outOfMaxScore 是两套评分字段，至少需一套
        has_score = not is_blank(rng.get("maxScore")) or not (
            is_blank(rng.get("outOfMinScore")) and is_blank(rng.get("outOfMaxScore"))
        )
        if not has_score:
            return "NEEDS_FIX", "rangeRule 无可用评分字段"
        return "EXECUTABLE", ""

    return "NEEDS_FIX", "未知 ruleType: " + rule_type


def classify_risk_rule(doc):
    rule = doc.get("riskRule")
    if is_blank(rule):
        return "MISSING", "riskRule 为空"

    static = rule.get("staticThreshold")
    adjust = rule.get("adjustmentFactor")

    if is_blank(static) and is_blank(adjust):
        return "NEEDS_FIX", "staticThreshold 与 adjustmentFactor 均为空"

    if not is_blank(static):
        missing = [
            f for f in ("operator", "thresholdValue", "riskLevel")
            if is_blank(static.get(f))
        ]
        if missing:
            return "NEEDS_FIX", "staticThreshold 缺字段: " + ",".join(missing)
        return "EXECUTABLE", ""

    # 只有 adjustmentFactor：计划 3 明确不自动执行复杂 AdjustmentFactor
    return "NEEDS_FIX", "仅有 adjustmentFactor，本轮不纳入自动执行"


def pct(part, whole):
    return "0.00%" if whole == 0 else "%.2f%%" % (100.0 * part / whole)


def section(title):
    print("\n" + "=" * 62)
    print(title)
    print("=" * 62)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", choices=("es", "json"), default="es")
    parser.add_argument("--dump-samples", action="store_true")
    args = parser.parse_args()

    if args.source == "es":
        docs = fetch_from_es()
        print("数据源: ES %s/%s" % (ES_URL, INDEX))
    else:
        docs = load_from_json()
        print("数据源: %s" % JSON_PATH)

    total = len(docs)
    print("文档总数: %d" % total)

    # 与另一数据源比对，暴露导入丢失
    if args.source == "es":
        try:
            json_total = len(load_from_json())
            flag = "一致" if json_total == total else "不一致"
            print("JSON 源文件条数: %d (%s)" % (json_total, flag))
        except Exception as exc:
            print("JSON 比对跳过: %s" % exc)

    section("1. 按 indicatorLevel 分布")
    levels = Counter(d.get("indicatorLevel") for d in docs)
    for lvl in sorted(levels, key=lambda x: (x is None, x)):
        print("  level %-6s %5d  %s" % (lvl, levels[lvl], pct(levels[lvl], total)))

    section("2. CalculationRule 覆盖与可执行性")
    calc_status = Counter()
    calc_reasons = Counter()
    calc_samples = {}
    rule_types = Counter()

    for d in docs:
        status, reason = classify_calculation_rule(d)
        calc_status[status] += 1
        if reason:
            calc_reasons[reason] += 1
        rule = d.get("calculationRule")
        if not is_blank(rule):
            rt = rule.get("ruleType")
            rule_types[str(rt).upper() if not is_blank(rt) else "(空)"] += 1
        key = status if status != "NEEDS_FIX" else "NEEDS_FIX:" + reason.split(":")[0]
        calc_samples.setdefault(key, []).append(d)

    has_calc = total - calc_status["MISSING"]
    print("  有 calculationRule : %5d  %s" % (has_calc, pct(has_calc, total)))
    print("  无 calculationRule : %5d  %s" % (calc_status["MISSING"], pct(calc_status["MISSING"], total)))
    print("  ruleType 分布:")
    for rt in sorted(rule_types):
        print("    %-12s %5d" % (rt, rule_types[rt]))
    print("  可执行性:")
    for st in ("EXECUTABLE", "NEEDS_FIX", "MISSING"):
        print("    %-12s %5d  %s" % (st, calc_status[st], pct(calc_status[st], total)))
    if calc_reasons:
        print("  不可执行原因明细:")
        for reason, cnt in calc_reasons.most_common():
            print("    %5d  %s" % (cnt, reason))

    section("3. RiskRule 覆盖与可执行性")
    risk_status = Counter()
    risk_reasons = Counter()
    risk_samples = {}
    for d in docs:
        status, reason = classify_risk_rule(d)
        risk_status[status] += 1
        if reason:
            risk_reasons[reason] += 1
        key = status if status != "NEEDS_FIX" else "NEEDS_FIX:" + reason.split(":")[0]
        risk_samples.setdefault(key, []).append(d)

    has_risk = total - risk_status["MISSING"]
    print("  有 riskRule : %5d  %s" % (has_risk, pct(has_risk, total)))
    print("  无 riskRule : %5d  %s" % (risk_status["MISSING"], pct(risk_status["MISSING"], total)))
    print("  可执行性:")
    for st in ("EXECUTABLE", "NEEDS_FIX", "MISSING"):
        print("    %-12s %5d  %s" % (st, risk_status[st], pct(risk_status[st], total)))
    if risk_reasons:
        print("  不可执行原因明细:")
        for reason, cnt in risk_reasons.most_common():
            print("    %5d  %s" % (cnt, reason))

    section("4. 两类规则的交叉覆盖")
    both = sum(
        1 for d in docs
        if classify_calculation_rule(d)[0] == "EXECUTABLE"
        and classify_risk_rule(d)[0] == "EXECUTABLE"
    )
    calc_only = sum(
        1 for d in docs
        if classify_calculation_rule(d)[0] == "EXECUTABLE"
        and classify_risk_rule(d)[0] != "EXECUTABLE"
    )
    risk_only = sum(
        1 for d in docs
        if classify_calculation_rule(d)[0] != "EXECUTABLE"
        and classify_risk_rule(d)[0] == "EXECUTABLE"
    )
    neither = total - both - calc_only - risk_only
    print("  两者均可执行     : %5d  %s" % (both, pct(both, total)))
    print("  仅计算规则可执行 : %5d  %s" % (calc_only, pct(calc_only, total)))
    print("  仅风险规则可执行 : %5d  %s" % (risk_only, pct(risk_only, total)))
    print("  两者均不可执行   : %5d  %s" % (neither, pct(neither, total)))

    section("5. RANGE 规则的表达式求值依赖")
    # calculationMethod 存的是 JavaScript 表达式，结构完整不等于 Java 8 可直接执行。
    # 这一节量化实现规则引擎时必须支持的语法特性。
    import re as _re
    feats = Counter()
    methods = Counter()
    range_docs = [
        d for d in docs
        if not is_blank(d.get("calculationRule"))
        and not is_blank(d["calculationRule"].get("rangeRule"))
    ]
    for d in range_docs:
        m = d["calculationRule"]["rangeRule"].get("calculationMethod") or ""
        methods[m] += 1
        if _re.search(r"\bx\b", m):
            feats["引用 x 变量"] += 1
        if "?" in m:
            feats["三元表达式 ?:"] += 1
        if "(" in m:
            feats["含括号嵌套"] += 1
        if "maxScore" in m:
            feats["引用 maxScore 变量"] += 1
        if "===" in m:
            feats["JS 严格相等 ==="] += 1
    print("  RANGE 规则数: %d，去重后表达式种类: %d" % (len(range_docs), len(methods)))
    for k, v in feats.most_common():
        print("    %5d  %s" % (v, k))

    section("6. 规则内容重复度")
    # 结构可执行但内容雷同，说明规则可能由模板批量填充而非逐指标设计。
    risk_json = Counter(
        json.dumps(d.get("riskRule"), sort_keys=True, ensure_ascii=False)
        for d in docs if not is_blank(d.get("riskRule"))
    )
    print("  RiskRule 去重后种类数: %d（共 %d 条）" % (len(risk_json), sum(risk_json.values())))
    for body, cnt in risk_json.most_common(3):
        print("    %5d  %s" % (cnt, body[:110]))
    binary_scores = Counter(
        (d["calculationRule"]["binaryRule"].get("trueScore"),
         d["calculationRule"]["binaryRule"].get("falseScore"))
        for d in docs
        if not is_blank(d.get("calculationRule"))
        and not is_blank(d["calculationRule"].get("binaryRule"))
    )
    print("  BinaryRule 评分组合去重: %d" % len(binary_scores))
    for k, v in binary_scores.most_common(5):
        print("    %5d  trueScore=%s falseScore=%s" % (v, k[0], k[1]))

    section("7. 可执行指标的 level 分布")
    exec_levels = Counter(
        d.get("indicatorLevel") for d in docs
        if classify_calculation_rule(d)[0] == "EXECUTABLE"
    )
    if exec_levels:
        for lvl in sorted(exec_levels, key=lambda x: (x is None, x)):
            print("  level %-6s %5d" % (lvl, exec_levels[lvl]))
    else:
        print("  无可执行指标")

    if args.dump_samples:
        section("8. 原始规则抽样（每类 %d 条）" % SAMPLE_SIZE)
        for key in sorted(calc_samples):
            print("\n-- CalculationRule / %s --" % key)
            for d in calc_samples[key][:SAMPLE_SIZE]:
                print("  id=%s level=%s name=%s" % (
                    d.get("id"), d.get("indicatorLevel"), d.get("name")))
                print("    %s" % json.dumps(d.get("calculationRule"), ensure_ascii=False))
        for key in sorted(risk_samples):
            print("\n-- RiskRule / %s --" % key)
            for d in risk_samples[key][:SAMPLE_SIZE]:
                print("  id=%s level=%s name=%s" % (
                    d.get("id"), d.get("indicatorLevel"), d.get("name")))
                print("    %s" % json.dumps(d.get("riskRule"), ensure_ascii=False))

    print("")


if __name__ == "__main__":
    main()
