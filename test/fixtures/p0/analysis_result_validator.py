"""AnalysisResult 输出校验器。

实现 documents/plan0/baseline/p0-05-core-schemas.md 第 8 节的字段约束与 8.4 交叉校验规则。
本模块只做校验，不发起网络请求，可独立单元测试。P3-02 落地 Java 版校验时以本文件为口径基准。

校验分三层，逐层短路：
  L1 解析层  能否从响应文本中取出一个 JSON 对象
  L2 字段层  必填性、类型、枚举、取值范围
  L3 交叉层  8.4 的字段间约束（适用性与合规状态的组合、引用范围、单位配对）

失败一律返回结构化原因，不抛异常、不静默通过——PLAN.md:169 要求失败不被静默吞掉。
"""

import json
import re

SCHEMA_VERSION = "1.0"

APPLICABILITY = {"APPLICABLE", "NOT_APPLICABLE", "UNKNOWN"}

COMPLIANCE_STATUS = {
    "COMPLIANT",
    "NON_COMPLIANT",
    "INSUFFICIENT_EVIDENCE",
    "NEEDS_REVIEW",
}

GAP_TYPE = {
    "MISSING_ACTION",
    "PROHIBITED_ACTION",
    "QUANTITATIVE_SHORTFALL",
    "QUANTITATIVE_EXCESS",
    "DOCUMENTATION_GAP",
}

# LLM 负责产出的字段。schemaVersion / id / analysisRunId / assessmentId /
# behaviorId / modelVersion / promptVersion / analyzedAt 由系统装配，不进入校验。
LLM_FIELDS = [
    "applicable",
    "requirement",
    "enterpriseFact",
    "complianceStatus",
    "gapType",
    "gapValue",
    "gapUnit",
    "confidence",
    "reasoning",
    "indicatorId",
    "regulationIds",
    "evidenceIds",
]

REQUIRED_NON_NULL = [
    "applicable",
    "enterpriseFact",
    "complianceStatus",
    "confidence",
    "reasoning",
    "indicatorId",
    "evidenceIds",
]

# 失败类别，对应 PLAN.md:178 要求覆盖的六类场景
FAIL_INVALID_JSON = "INVALID_JSON"
FAIL_NOT_OBJECT = "NOT_OBJECT"
FAIL_MISSING_FIELD = "MISSING_FIELD"
FAIL_NULL_REQUIRED = "NULL_REQUIRED_FIELD"
FAIL_BAD_ENUM = "BAD_ENUM"
FAIL_BAD_TYPE = "BAD_TYPE"
FAIL_OUT_OF_RANGE = "OUT_OF_RANGE"
FAIL_EMPTY_STRING_ENUM = "EMPTY_STRING_IN_ENUM"
FAIL_CROSS_RULE = "CROSS_RULE_VIOLATION"
FAIL_UNKNOWN_REFERENCE = "UNKNOWN_REFERENCE"
FAIL_EXTRA_TEXT = "EXTRA_TEXT_AROUND_JSON"
FAIL_TIMEOUT = "TIMEOUT"


class ValidationResult(object):
    """校验结果。errors 非空即失败；warnings 不影响通过与否。"""

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

    def warn(self, code, field, detail):
        self.warnings.append({"code": code, "field": field, "detail": detail})

    def to_dict(self):
        return {
            "passed": self.passed,
            "errors": self.errors,
            "warnings": self.warnings,
            "hadExtraText": self.had_extra_text,
        }


def extract_json_object(text):
    """从响应文本中取出第一个完整 JSON 对象。

    返回 (对象, 是否存在多余文本)。取不出时返回 (None, False)。

    模型常见偏差是包裹 Markdown 代码块或添加前后说明。这里做容错提取而非直接判失败，
    但会标记 had_extra_text——Prompt 明确要求只输出 JSON，多余文本属于未遵守指令，
    需要计入统计，不能因为"能解析"就当作完全正常。
    """
    if text is None:
        return None, False

    stripped = text.strip()
    if not stripped:
        return None, False

    # 直接可解析：完全符合要求的情形
    try:
        return json.loads(stripped), False
    except ValueError:
        pass

    # 去掉 Markdown 代码块围栏后重试
    fenced = re.sub(r"^```(?:json)?\s*", "", stripped)
    fenced = re.sub(r"\s*```$", "", fenced)
    if fenced != stripped:
        try:
            return json.loads(fenced.strip()), True
        except ValueError:
            stripped = fenced.strip()

    # 括号配对扫描，跳过字符串字面量与转义
    start = stripped.find("{")
    if start < 0:
        return None, False

    depth = 0
    in_string = False
    escaped = False
    for i in range(start, len(stripped)):
        ch = stripped[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                candidate = stripped[start:i + 1]
                try:
                    return json.loads(candidate), True
                except ValueError:
                    return None, False
    return None, False


def _check_enum(result, obj, field, allowed, required):
    """校验枚举字段。空串单列错误码——空串既非有效值也非 null，是信息丢失。"""
    if field not in obj:
        if required:
            result.error(FAIL_MISSING_FIELD, field, "必填字段缺失")
        return None

    value = obj[field]
    if value is None:
        if required:
            result.error(FAIL_NULL_REQUIRED, field, "必填字段为 null")
        return None

    if not isinstance(value, str):
        result.error(FAIL_BAD_TYPE, field, "期望字符串，实际 %s" % type(value).__name__)
        return None

    if value == "":
        result.error(FAIL_EMPTY_STRING_ENUM, field, "枚举字段为空串，语义未知须用显式枚举值")
        return None

    if value not in allowed:
        result.error(FAIL_BAD_ENUM, field, "取值 %r 不在允许集合 %s 内" % (value, sorted(allowed)))
        return None

    return value


def _check_string(result, obj, field, required):
    if field not in obj:
        if required:
            result.error(FAIL_MISSING_FIELD, field, "必填字段缺失")
        return None
    value = obj[field]
    if value is None:
        if required:
            result.error(FAIL_NULL_REQUIRED, field, "必填字段为 null")
        return None
    if not isinstance(value, str):
        result.error(FAIL_BAD_TYPE, field, "期望字符串，实际 %s" % type(value).__name__)
        return None
    if required and not value.strip():
        result.error(FAIL_NULL_REQUIRED, field, "必填字符串为空白")
        return None
    return value


def _check_id_list(result, obj, field, required):
    if field not in obj:
        if required:
            result.error(FAIL_MISSING_FIELD, field, "必填字段缺失")
        return None
    value = obj[field]
    if value is None:
        if required:
            result.error(FAIL_NULL_REQUIRED, field, "必填字段为 null")
        return None
    if not isinstance(value, list):
        result.error(FAIL_BAD_TYPE, field, "期望数组，实际 %s" % type(value).__name__)
        return None
    if required and not value:
        result.error(FAIL_NULL_REQUIRED, field, "必填数组为空")
        return None
    for item in value:
        if not isinstance(item, str) or not item.strip():
            result.error(FAIL_BAD_TYPE, field, "数组元素须为非空字符串，实际 %r" % (item,))
            return None
    return value


def validate(response_text, context):
    """校验一次 LLM 响应。

    context 提供引用范围与回填校验所需的权威值：
        allowedEvidenceIds  允许出现在 evidenceIds 中的 ID 集合
        allowedRegulationIds 候选法规 ID 集合
        expectedIndicatorId 目标指标 ID
    """
    result = ValidationResult()

    # L1 解析层
    obj, had_extra = extract_json_object(response_text)
    result.had_extra_text = had_extra
    if obj is None:
        result.error(FAIL_INVALID_JSON, None, "无法从响应中解析出 JSON 对象")
        return result
    if not isinstance(obj, dict):
        result.error(FAIL_NOT_OBJECT, None, "解析结果为 %s，期望对象" % type(obj).__name__)
        return result

    result.parsed = obj
    if had_extra:
        # 记为错误而非警告：Prompt 硬性要求只输出 JSON，多余文本即未遵守指令。
        # 但 parsed 已可用，调用方可据此决定是否接受降级结果。
        result.error(FAIL_EXTRA_TEXT, None, "JSON 之外存在多余文本或代码块围栏")

    # L2 字段层
    applicable = _check_enum(result, obj, "applicable", APPLICABILITY, True)
    status = _check_enum(result, obj, "complianceStatus", COMPLIANCE_STATUS, True)
    gap_type = _check_enum(result, obj, "gapType", GAP_TYPE, False)

    _check_string(result, obj, "enterpriseFact", True)
    _check_string(result, obj, "reasoning", True)
    requirement = _check_string(result, obj, "requirement", False)
    indicator_id = _check_string(result, obj, "indicatorId", True)

    evidence_ids = _check_id_list(result, obj, "evidenceIds", True)
    regulation_ids = _check_id_list(result, obj, "regulationIds", False)

    confidence = None
    if "confidence" not in obj:
        result.error(FAIL_MISSING_FIELD, "confidence", "必填字段缺失")
    elif obj["confidence"] is None:
        result.error(FAIL_NULL_REQUIRED, "confidence", "必填字段为 null")
    elif isinstance(obj["confidence"], bool) or not isinstance(obj["confidence"], (int, float)):
        result.error(FAIL_BAD_TYPE, "confidence", "期望数值，实际 %s" % type(obj["confidence"]).__name__)
    else:
        confidence = float(obj["confidence"])
        if confidence < 0.0 or confidence > 1.0:
            result.error(FAIL_OUT_OF_RANGE, "confidence", "取值 %s 超出 [0.0, 1.0]" % confidence)

    gap_value = obj.get("gapValue")
    if gap_value is not None:
        if isinstance(gap_value, bool) or not isinstance(gap_value, (int, float)):
            result.error(FAIL_BAD_TYPE, "gapValue", "期望数值或 null，实际 %s" % type(gap_value).__name__)
            gap_value = None

    gap_unit = obj.get("gapUnit")
    if gap_unit is not None and not isinstance(gap_unit, str):
        result.error(FAIL_BAD_TYPE, "gapUnit", "期望字符串或 null，实际 %s" % type(gap_unit).__name__)
        gap_unit = None

    for key in obj:
        if key not in LLM_FIELDS:
            result.warn("UNEXPECTED_FIELD", key, "响应包含 Schema 未定义的字段")

    # L3 交叉层，对应 p0-05-core-schemas.md 8.4
    # 规则 3：NOT_APPLICABLE 不得判不合规
    if applicable == "NOT_APPLICABLE" and status == "NON_COMPLIANT":
        result.error(
            FAIL_CROSS_RULE,
            "complianceStatus",
            "applicable=NOT_APPLICABLE 时不得为 NON_COMPLIANT（8.4 规则 3）",
        )

    # 规则 4：UNKNOWN 只能落在证据不足或需复核
    if applicable == "UNKNOWN" and status not in (None, "INSUFFICIENT_EVIDENCE", "NEEDS_REVIEW"):
        result.error(
            FAIL_CROSS_RULE,
            "complianceStatus",
            "applicable=UNKNOWN 时只能为 INSUFFICIENT_EVIDENCE 或 NEEDS_REVIEW（8.4 规则 4）",
        )

    # 规则 5：不合规必须给出差距类型
    if status == "NON_COMPLIANT" and gap_type is None:
        result.error(
            FAIL_CROSS_RULE,
            "gapType",
            "complianceStatus=NON_COMPLIANT 时 gapType 必填（8.4 规则 5）",
        )

    # 规则 6：定量差距必须带单位
    if gap_value is not None and (gap_unit is None or not str(gap_unit).strip()):
        result.error(
            FAIL_CROSS_RULE,
            "gapUnit",
            "gapValue 非空时 gapUnit 必填（8.4 规则 6）",
        )

    # applicable=APPLICABLE 时 requirement 与 regulationIds 必填
    if applicable == "APPLICABLE":
        if not requirement:
            result.error(FAIL_CROSS_RULE, "requirement", "applicable=APPLICABLE 时 requirement 必填")
        if not regulation_ids:
            result.error(FAIL_CROSS_RULE, "regulationIds", "applicable=APPLICABLE 时 regulationIds 必填")

    # 规则 1：evidenceIds 须为给定证据集合的子集
    allowed_evidence = set(context.get("allowedEvidenceIds") or [])
    if evidence_ids and allowed_evidence:
        unknown = [e for e in evidence_ids if e not in allowed_evidence]
        if unknown:
            result.error(
                FAIL_UNKNOWN_REFERENCE,
                "evidenceIds",
                "引用了给定集合外的证据 ID %s（8.4 规则 1，属幻觉引用）" % unknown,
            )

    # 规则 2：regulationIds 须来自候选法规
    allowed_regulations = set(context.get("allowedRegulationIds") or [])
    if regulation_ids and allowed_regulations:
        unknown = [r for r in regulation_ids if r not in allowed_regulations]
        if unknown:
            result.error(
                FAIL_UNKNOWN_REFERENCE,
                "regulationIds",
                "引用了候选外的法规 ID %s（8.4 规则 2，属未经检索的记忆输出）" % unknown,
            )

    # indicatorId 必须原样回填
    expected_indicator = context.get("expectedIndicatorId")
    if indicator_id and expected_indicator and indicator_id != expected_indicator:
        result.error(
            FAIL_UNKNOWN_REFERENCE,
            "indicatorId",
            "回填值 %r 与目标指标 %r 不一致" % (indicator_id, expected_indicator),
        )

    return result
