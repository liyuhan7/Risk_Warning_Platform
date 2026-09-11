# -*- coding: utf-8 -*-
"""P1-10 字段级抽取验证：将 P1-09 期望事实标注与真实抽取行为三维度比对。

维度：
  1. 事实级召回：期望事实中被抽取行为覆盖的比例（按段内配对）。
  2. 字段级准确率：已配对事实的各字段精确一致率。
  3. 引用正确性：配对行为的证据引用是否落在期望事实所在段，及跨段引用比例。

用法：
  py -3 test\\fixtures\\p1\\fact_extraction_compare.py ^
      --expected test\\fixtures\\p1\\expected-facts\\华东智联供应链材料-v1.expected.json ^
      --actual storage\\es_out_68_hits.json ^
      --segments test\\fixtures\\p1\\expected-facts\\segment-evidence-map-68.json ^
      --output storage\\compare-report-68.txt
"""
import argparse
import io
import json
import re
import sys

MATCH_THRESHOLD = 0.30
STOP = set("的了吧呢与和或及、，。；：？！（）()《》“”\"'…—·-|0123456789.%")


def load(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def clean(value):
    if value is None:
        return ""
    return "".join(ch for ch in str(value) if ch not in STOP)


def bigrams(text):
    text = clean(text)
    return set(text[index:index + 2] for index in range(len(text) - 1))


def action_sim(expected_action, actual_action):
    left = bigrams(expected_action)
    right = bigrams(actual_action)
    if not left or not right:
        return 0.0
    return len(left & right) / float(max(len(left), len(right)))


def quantity_matches(expected, actual):
    """返回 (匹配, 说明)。数值按数值相等，null 与缺失视为一致。"""
    exp_q = expected.get("quantitativeData")
    exp_u = expected.get("quantitativeUnit")
    act_q = actual.get("quantitativeData")
    act_u = actual.get("quantitativeUnit")
    if exp_q is None and act_q is None:
        return 1.0, "both-null"
    if exp_q is None or act_q is None:
        return 0.0, "one-null"
    if float(exp_q) == float(act_q) and (exp_u or "") == (act_u or ""):
        return 1.0, "q+u"
    if float(exp_q) == float(act_q):
        return 0.5, "q-only"
    return 0.0, "q-diff"


def pair_score(expected_fact, actual):
    sim = action_sim(expected_fact["action"], actual.get("action") or "")
    status = 0.3 if expected_fact.get("status") == actual.get("status") else -0.1
    quant, _ = quantity_matches(expected_fact, actual)
    return 0.5 * sim + status + 0.2 * quant


def same_value(left, right):
    if left is None and right is None:
        return True
    if left is None or right is None:
        return False
    if isinstance(left, (int, float)) and isinstance(right, (int, float)):
        return float(left) == float(right)
    return str(left) == str(right)


def greedy_match(expected_facts, actual_list):
    """段内贪心一对一配对，返回 (matched_pairs, unmatched_expected, used_actual)。"""
    pairs = []
    for exp_index, exp_fact in enumerate(expected_facts):
        for act_index, actual in enumerate(actual_list):
            pairs.append((pair_score(exp_fact, actual), exp_index, act_index))
    pairs.sort(key=lambda item: item[0], reverse=True)
    matched = []
    used_actual = set()
    used_expected = set()
    for score, exp_index, act_index in pairs:
        if score < MATCH_THRESHOLD:
            break
        if exp_index in used_expected or act_index in used_actual:
            continue
        matched.append((exp_index, act_index, score))
        used_expected.add(exp_index)
        used_actual.add(act_index)
    unmatched = [index for index in range(len(expected_facts)) if index not in used_expected]
    return matched, unmatched, sorted(used_actual)


def main():
    parser = argparse.ArgumentParser(description="P1-10 字段级抽取验证比对")
    parser.add_argument("--expected", required=True)
    parser.add_argument("--actual", required=True)
    parser.add_argument("--segments", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    expected_data = load(args.expected)
    actual_hits = load(args.actual)["hits"]["hits"]
    segment_map = load(args.segments)

    seg_evidence = {}
    for seg in segment_map["segments"]:
        seg_evidence[seg["segmentIndex"]] = set(seg["evidenceIds"])
    evidence_seg = {}
    for seg_index, ids in seg_evidence.items():
        for evidence_id in ids:
            evidence_seg[evidence_id] = seg_index

    expected_by_seg = {}
    for seg in expected_data["segments"]:
        expected_by_seg[seg["segmentIndex"]] = seg.get("expectedFacts", [])

    actual_records = []
    for hit in actual_hits:
        source = hit["_source"]
        evidence_ids = source.get("evidenceIds") or []
        segs = {evidence_seg[eid] for eid in evidence_ids if eid in evidence_seg}
        actual_records.append({
            "id": hit.get("_id"),
            "subject": source.get("subject"),
            "action": source.get("action"),
            "object": source.get("object"),
            "status": source.get("status"),
            "behaviorDate": source.get("behaviorDate"),
            "quantitativeData": source.get("quantitativeData"),
            "quantitativeUnit": source.get("quantitativeUnit"),
            "description": source.get("description"),
            "evidenceIds": evidence_ids,
            "segments": segs,
        })

    # 三段比对统计
    total_matched = 0
    total_expected = 0
    field_hits = {"subject": 0, "action": 0, "object": 0, "status": 0,
                  "quantitativeData": 0, "quantitativeUnit": 0, "behaviorDate": 0}
    field_total = dict(field_hits)
    cross_segment_behaviors = 0
    detail_lines = []
    row_format = "  %-34s %-30s %-12s %5.2f  %s"

    for seg_index in sorted(seg_evidence.keys()):
        expected_facts = expected_by_seg.get(seg_index, [])
        seg_actual = [rec for rec in actual_records if rec["segments"] == {seg_index}]
        matched, unmatched, used = greedy_match(expected_facts, seg_actual)
        total_expected += len(expected_facts)
        total_matched += len(matched)
        detail_lines.append("")
        detail_lines.append("== 段 %d：期望 %d 条，段内行为 %d 条，匹配 %d 条 =="
                            % (seg_index, len(expected_facts), len(seg_actual), len(matched)))
        for exp_index, act_index, score in matched:
            exp_fact = expected_facts[exp_index]
            actual = seg_actual[act_index]
            for field in field_hits:
                field_total[field] += 1
                if same_value(exp_fact.get(field), actual.get(field)):
                    field_hits[field] += 1
            detail_lines.append(row_format % (
                exp_fact.get("action") or "", actual.get("action") or "",
                exp_fact.get("status") or "", score,
                "matched"))
        for exp_index in unmatched:
            exp_fact = expected_facts[exp_index]
            detail_lines.append(row_format % (
                exp_fact.get("action") or "", "-", exp_fact.get("status") or "", 0.0,
                "UNMATCHED"))

    # 引用正确性：行为引用的证据是否落在单一期望段（跨段引用视为引用错误）
    actual_total = len(actual_records)
    cross_segment_behaviors = sum(1 for rec in actual_records if len(rec["segments"]) > 1)
    unassigned_behaviors = sum(1 for rec in actual_records if not rec["segments"])
    single_segment = actual_total - cross_segment_behaviors - unassigned_behaviors

    lines = []
    lines.append("P1-10 字段级抽取验证报告（fact-extract-v1.1 / 评估68）")
    lines.append("=" * 70)
    lines.append("")
    lines.append("【1. 事实级召回】")
    lines.append("  期望事实：%d 条，实际抽取行为：%d 条，配对成功：%d 条"
                 % (total_expected, actual_total, total_matched))
    lines.append("  召回率：%.1f%%" % (100.0 * total_matched / total_expected if total_expected else 0.0))
    lines.append("")
    lines.append("【2. 字段级准确率】（配对 %d 条的字段精确一致率）" % total_matched)
    for field in field_hits:
        rate = 100.0 * field_hits[field] / field_total[field] if field_total[field] else 0.0
        lines.append("  %-20s %d/%d = %.1f%%" % (field, field_hits[field],
                                                 field_total[field], rate))
    lines.append("")
    lines.append("【3. 引用正确性】")
    lines.append("  引用证据全部落在单一期望段的行为：%d/%d = %.1f%%"
                 % (single_segment, actual_total,
                    100.0 * single_segment / actual_total if actual_total else 0.0))
    lines.append("  跨段引用行为（引用不止一段证据）：%d/%d = %.1f%%"
                 % (cross_segment_behaviors, actual_total,
                    100.0 * cross_segment_behaviors / actual_total if actual_total else 0.0))
    lines.append("  未归属任意段的引用行为：%d 条" % unassigned_behaviors)
    lines.append("")
    lines.append("【逐段明细】")
    lines.extend(detail_lines)

    report = "\n".join(lines) + "\n"
    with io.open(args.output, "w", encoding="utf-8") as handle:
        handle.write(report)
    sys.stdout.write("report written: %s\n" % args.output)


if __name__ == "__main__":
    main()