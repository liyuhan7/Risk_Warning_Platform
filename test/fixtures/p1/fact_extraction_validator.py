"""Fact Extraction v1.0 离线三层校验器。

本脚本不发起网络请求。L2 从冻结 JSON Schema 读取必填字段、枚举、范围、
日期格式和单位长度，再执行当前契约所需的确定性校验；不实现通用 JSON Schema 引擎。
"""

import argparse
import json
import pathlib
import re
import sys


BASE_DIR = pathlib.Path(__file__).resolve().parent
DEFAULT_SCHEMA = BASE_DIR / "schemas" / "fact-extraction-v1.0.json"
DEFAULT_SAMPLES = BASE_DIR / "fact-extraction-samples.json"

FAIL_INVALID_JSON = "INVALID_JSON"
FAIL_NOT_OBJECT = "NOT_OBJECT"
FAIL_EXTRA_TEXT = "EXTRA_TEXT_AROUND_JSON"
FAIL_MISSING_FIELD = "MISSING_FIELD"
FAIL_NULL_REQUIRED = "NULL_REQUIRED_FIELD"
FAIL_BAD_TYPE = "BAD_TYPE"
FAIL_BAD_ENUM = "BAD_ENUM"
FAIL_EMPTY_ENUM = "EMPTY_STRING_IN_ENUM"
FAIL_OUT_OF_RANGE = "OUT_OF_RANGE"
FAIL_BAD_FORMAT = "BAD_FORMAT"
FAIL_CROSS_RULE = "CROSS_RULE_VIOLATION"
FAIL_UNKNOWN_REFERENCE = "UNKNOWN_REFERENCE"
FAIL_UNEXPECTED_FIELD = "UNEXPECTED_FIELD"
FAIL_PROHIBITED_INFERENCE = "PROHIBITED_INFERENCE"

PROHIBITED_FIELDS = {
    "complianceStatus",
    "legalConclusion",
    "regulationIds",
    "riskLevel",
    "riskScore",
    "remediation",
}

PROHIBITED_TEXT = re.compile(
    r"(?:违反[^，。；]*(?:法|规定|条例)|(?:属于|构成|判定为|认定为)(?:违法|违规|不合规)|(?:高|中|低)风险|应当整改)"
)


class ValidationResult(object):
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.parsed = None
        self.had_extra_text = False

    @property
    def passed(self):
        return not self.errors

    def error(self, code, field, detail):
        self.errors.append({"code": code, "field": field, "detail": detail})

    def to_dict(self):
        return {
            "passed": self.passed,
            "errors": self.errors,
            "warnings": self.warnings,
            "hadExtraText": self.had_extra_text,
        }


def extract_json_object(text):
    """按直接解析、剥离围栏、括号配对的顺序取出首个 JSON 对象。"""
    if text is None or not str(text).strip():
        return None, False
    stripped = str(text).strip()
    try:
        return json.loads(stripped), False
    except ValueError:
        pass

    fenced = re.sub(r"^```(?:json)?\s*", "", stripped)
    fenced = re.sub(r"\s*```$", "", fenced)
    if fenced != stripped:
        try:
            return json.loads(fenced.strip()), True
        except ValueError:
            stripped = fenced.strip()

    start = stripped.find("{")
    if start < 0:
        return None, False
    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(stripped)):
        character = stripped[index]
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(stripped[start:index + 1]), True
                except ValueError:
                    return None, False
    return None, False


def _field_path(fact_index, field=None):
    base = "facts[%d]" % fact_index
    return base if field is None else "%s.%s" % (base, field)


def _is_number(value):
    return not isinstance(value, bool) and isinstance(value, (int, float))


def _check_non_blank_string(result, fact, fact_index, field, required):
    path = _field_path(fact_index, field)
    if field not in fact:
        if required:
            result.error(FAIL_MISSING_FIELD, path, "必填字段缺失")
        return None
    value = fact[field]
    if value is None:
        if required:
            result.error(FAIL_NULL_REQUIRED, path, "必填字段为 null")
        return None
    if not isinstance(value, str):
        result.error(FAIL_BAD_TYPE, path, "期望字符串")
        return None
    if not value.strip():
        result.error(FAIL_NULL_REQUIRED, path, "字符串为空白")
        return None
    return value


def _validate_fact(result, fact, fact_index, allowed_evidence, fact_schema):
    path = _field_path(fact_index)
    if not isinstance(fact, dict):
        result.error(FAIL_BAD_TYPE, path, "fact 必须是对象")
        return

    properties = fact_schema["properties"]
    required = set(fact_schema["required"])
    unexpected = sorted(set(fact) - set(properties))
    for field in unexpected:
        result.error(FAIL_UNEXPECTED_FIELD, _field_path(fact_index, field), "字段不属于模型输出契约")
        if field in PROHIBITED_FIELDS:
            result.error(FAIL_PROHIBITED_INFERENCE, _field_path(fact_index, field), "禁止输出风险、法规或合规判断字段")

    subject = _check_non_blank_string(result, fact, fact_index, "subject", "subject" in required)
    action = _check_non_blank_string(result, fact, fact_index, "action", "action" in required)
    description = _check_non_blank_string(result, fact, fact_index, "description", "description" in required)

    if "object" in fact and fact["object"] is not None:
        _check_non_blank_string(result, fact, fact_index, "object", False)

    status_path = _field_path(fact_index, "status")
    if "status" not in fact:
        result.error(FAIL_MISSING_FIELD, status_path, "必填字段缺失")
    elif not isinstance(fact["status"], str):
        result.error(FAIL_BAD_TYPE, status_path, "status 必须是字符串")
    elif fact["status"] == "":
        result.error(FAIL_EMPTY_ENUM, status_path, "状态不得为空串")
    elif fact["status"] not in properties["status"]["enum"]:
        result.error(FAIL_BAD_ENUM, status_path, "状态不在允许枚举内")

    if "behaviorDate" in fact:
        date_path = _field_path(fact_index, "behaviorDate")
        date_value = fact["behaviorDate"]
        date_pattern = properties["behaviorDate"]["pattern"]
        if not isinstance(date_value, str):
            result.error(FAIL_BAD_TYPE, date_path, "behaviorDate 必须是字符串")
        elif re.fullmatch(date_pattern, date_value) is None:
            result.error(FAIL_BAD_FORMAT, date_path, "仅允许 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm:ss")

    quantitative_present = "quantitativeData" in fact
    if quantitative_present and not _is_number(fact["quantitativeData"]):
        result.error(FAIL_BAD_TYPE, _field_path(fact_index, "quantitativeData"), "quantitativeData 必须是数值")

    if "quantitativeUnit" in fact:
        unit = _check_non_blank_string(result, fact, fact_index, "quantitativeUnit", False)
        max_length = properties["quantitativeUnit"]["maxLength"]
        if unit is not None and len(unit) > max_length:
            result.error(FAIL_OUT_OF_RANGE, _field_path(fact_index, "quantitativeUnit"), "单位超过 %d 字符" % max_length)
    elif quantitative_present:
        result.error(FAIL_CROSS_RULE, _field_path(fact_index, "quantitativeUnit"), "有定量值时单位必填")

    confidence_path = _field_path(fact_index, "confidence")
    if "confidence" not in fact:
        result.error(FAIL_MISSING_FIELD, confidence_path, "必填字段缺失")
    elif not _is_number(fact["confidence"]):
        result.error(FAIL_BAD_TYPE, confidence_path, "confidence 必须是数值")
    else:
        confidence = float(fact["confidence"])
        minimum = properties["confidence"]["minimum"]
        maximum = properties["confidence"]["maximum"]
        if confidence < minimum or confidence > maximum:
            result.error(FAIL_OUT_OF_RANGE, confidence_path, "confidence 超出 [0,1]")

    evidence_path = _field_path(fact_index, "evidenceIds")
    evidence_ids = fact.get("evidenceIds")
    if "evidenceIds" not in fact:
        result.error(FAIL_MISSING_FIELD, evidence_path, "必填字段缺失")
    elif not isinstance(evidence_ids, list):
        result.error(FAIL_BAD_TYPE, evidence_path, "evidenceIds 必须是数组")
    elif not evidence_ids:
        result.error(FAIL_NULL_REQUIRED, evidence_path, "evidenceIds 不得为空")
    else:
        if any(not isinstance(item, str) or not item.strip() for item in evidence_ids):
            result.error(FAIL_BAD_TYPE, evidence_path, "证据 ID 必须是非空字符串")
        if len(evidence_ids) != len(set(evidence_ids)):
            result.error(FAIL_CROSS_RULE, evidence_path, "证据 ID 不得重复")
        unknown = [item for item in evidence_ids if item not in allowed_evidence]
        if unknown:
            result.error(FAIL_UNKNOWN_REFERENCE, evidence_path, "引用输入集合外证据 %s" % unknown)

    for field, value in (("subject", subject), ("action", action), ("description", description)):
        if value and PROHIBITED_TEXT.search(value):
            result.error(FAIL_PROHIBITED_INFERENCE, _field_path(fact_index, field), "文本包含风险、法规或合规判断")


def validate(response_text, allowed_evidence_ids, schema):
    result = ValidationResult()

    obj, had_extra = extract_json_object(response_text)
    result.had_extra_text = had_extra
    if obj is None:
        result.error(FAIL_INVALID_JSON, None, "无法解析 JSON")
        return result
    if not isinstance(obj, dict):
        result.error(FAIL_NOT_OBJECT, None, "根节点必须是对象")
        return result
    result.parsed = obj
    if had_extra:
        result.error(FAIL_EXTRA_TEXT, None, "JSON 外存在说明文字或 Markdown 围栏")

    root_properties = set(schema["properties"])
    for field in sorted(set(obj) - root_properties):
        result.error(FAIL_UNEXPECTED_FIELD, field, "根对象包含未声明字段")
    if "facts" not in obj:
        result.error(FAIL_MISSING_FIELD, "facts", "根对象缺少 facts")
        return result
    if not isinstance(obj["facts"], list):
        result.error(FAIL_BAD_TYPE, "facts", "facts 必须是数组")
        return result

    fact_schema = schema["$defs"]["fact"]
    allowed_evidence = set(allowed_evidence_ids or [])
    for fact_index, fact in enumerate(obj["facts"]):
        _validate_fact(result, fact, fact_index, allowed_evidence, fact_schema)
    return result


def run_samples(samples_path, schema_path):
    with schema_path.open("r", encoding="utf-8") as stream:
        schema = json.load(stream)
    with samples_path.open("r", encoding="utf-8") as stream:
        samples = json.load(stream)

    rows = []
    all_matched = True
    for sample in samples:
        result = validate(sample.get("rawResponse"), sample.get("allowedEvidenceIds"), schema)
        actual_codes = sorted({item["code"] for item in result.errors})
        expected_codes = sorted(sample.get("expectCodes") or [])
        matched = result.passed == sample["expectPass"] and all(
            code in actual_codes for code in expected_codes
        )
        all_matched = all_matched and matched
        rows.append({
            "id": sample["id"],
            "category": sample["category"],
            "expectPass": sample["expectPass"],
            "actualPass": result.passed,
            "expectedCodes": expected_codes,
            "actualCodes": actual_codes,
            "matched": matched,
        })
    return all_matched, rows


def main():
    parser = argparse.ArgumentParser(description="运行 Fact Extraction v1.0 离线样本")
    parser.add_argument("--samples", type=pathlib.Path, default=DEFAULT_SAMPLES)
    parser.add_argument("--schema", type=pathlib.Path, default=DEFAULT_SCHEMA)
    args = parser.parse_args()
    all_matched, rows = run_samples(args.samples, args.schema)
    print(json.dumps({"allMatched": all_matched, "total": len(rows), "results": rows},
                     ensure_ascii=False, indent=2))
    return 0 if all_matched else 1


if __name__ == "__main__":
    sys.exit(main())
