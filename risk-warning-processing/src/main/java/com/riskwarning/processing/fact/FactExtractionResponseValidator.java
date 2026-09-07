package com.riskwarning.processing.fact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.fact.FactExtractionResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按 fact-extraction-v1.0 执行 L1 解析、L2 字段和 L3 业务交叉校验。
 * 规则参数从随应用发布的冻结 Schema 读取，避免校验值与契约分别维护。
 */
@Component
public class FactExtractionResponseValidator {

    public static final String INVALID_JSON = "INVALID_JSON";
    public static final String NOT_OBJECT = "NOT_OBJECT";
    public static final String EXTRA_TEXT_AROUND_JSON = "EXTRA_TEXT_AROUND_JSON";
    public static final String MISSING_FIELD = "MISSING_FIELD";
    public static final String NULL_REQUIRED_FIELD = "NULL_REQUIRED_FIELD";
    public static final String BAD_TYPE = "BAD_TYPE";
    public static final String BAD_ENUM = "BAD_ENUM";
    public static final String EMPTY_STRING_IN_ENUM = "EMPTY_STRING_IN_ENUM";
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
    public static final String BAD_FORMAT = "BAD_FORMAT";
    public static final String CROSS_RULE_VIOLATION = "CROSS_RULE_VIOLATION";
    public static final String UNKNOWN_REFERENCE = "UNKNOWN_REFERENCE";
    public static final String UNEXPECTED_FIELD = "UNEXPECTED_FIELD";
    public static final String PROHIBITED_INFERENCE = "PROHIBITED_INFERENCE";

    private static final Set<String> PROHIBITED_FIELDS = new HashSet<>(Arrays.asList(
            "complianceStatus", "legalConclusion", "regulationIds", "riskLevel",
            "riskScore", "remediation"));
    private static final Pattern PROHIBITED_TEXT = Pattern.compile(
            "(?:违反[^，。；]*(?:法|规定|条例)|(?:属于|构成|判定为|认定为)(?:违法|违规|不合规)|(?:高|中|低)风险|应当整改)");

    private final ObjectMapper objectMapper;
    private final JsonNode schema;
    private final JsonNode factSchema;
    private final Set<String> requiredFields;
    private final Set<String> statusValues;
    private final Pattern behaviorDatePattern;
    private final int unitMaxLength;
    private final double confidenceMinimum;
    private final double confidenceMaximum;

    public FactExtractionResponseValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream input = new ClassPathResource(
                "fact-extraction/fact-extraction-v1.0.json").getInputStream()) {
            this.schema = objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 Fact Extraction Schema", exception);
        }
        this.factSchema = schema.path("$defs").path("fact");
        this.requiredFields = textSet(factSchema.path("required"));
        this.statusValues = textSet(factSchema.path("properties").path("status").path("enum"));
        this.behaviorDatePattern = Pattern.compile(factSchema.path("properties")
                .path("behaviorDate").path("pattern").asText());
        this.unitMaxLength = factSchema.path("properties").path("quantitativeUnit")
                .path("maxLength").asInt();
        this.confidenceMinimum = factSchema.path("properties").path("confidence")
                .path("minimum").asDouble();
        this.confidenceMaximum = factSchema.path("properties").path("confidence")
                .path("maximum").asDouble();
    }

    public FactExtractionValidationResult validate(String rawResponse, Set<String> allowedEvidenceIds) {
        FactExtractionValidationResult result = new FactExtractionValidationResult();
        ParsedJson parsed = parse(rawResponse);
        if (parsed.node == null) {
            result.addError(INVALID_JSON, null, "无法解析 JSON");
            return result;
        }
        if (!parsed.node.isObject()) {
            result.addError(NOT_OBJECT, null, "根节点必须是对象");
            return result;
        }
        if (parsed.extraText) {
            result.addError(EXTRA_TEXT_AROUND_JSON, null, "JSON 外存在说明文字或 Markdown 围栏");
        }

        JsonNode root = parsed.node;
        rejectUnexpected(result, root, schema.path("properties"), "");
        JsonNode facts = root.get("facts");
        if (facts == null) {
            result.addError(MISSING_FIELD, "facts", "根对象缺少 facts");
            return result;
        }
        if (!facts.isArray()) {
            result.addError(BAD_TYPE, "facts", "facts 必须是数组");
            return result;
        }
        Set<String> evidenceIds = allowedEvidenceIds == null
                ? java.util.Collections.emptySet() : allowedEvidenceIds;
        for (int index = 0; index < facts.size(); index++) {
            validateFact(result, facts.get(index), index, evidenceIds);
        }
        if (result.isValid()) {
            try {
                result.setResponse(objectMapper.treeToValue(root, FactExtractionResponse.class));
            } catch (IOException exception) {
                result.addError(BAD_TYPE, null, "响应无法映射到事实对象");
            }
        }
        return result;
    }

    private void validateFact(FactExtractionValidationResult result, JsonNode fact,
                              int index, Set<String> allowedEvidenceIds) {
        String base = "facts[" + index + "]";
        if (!fact.isObject()) {
            result.addError(BAD_TYPE, base, "fact 必须是对象");
            return;
        }
        JsonNode properties = factSchema.path("properties");
        rejectUnexpected(result, fact, properties, base + ".");
        for (String field : requiredFields) {
            if (!fact.has(field)) {
                result.addError(MISSING_FIELD, base + "." + field, "必填字段缺失");
            }
        }

        validateNonBlank(result, fact, base, "subject", true);
        validateNonBlank(result, fact, base, "action", true);
        validateNonBlank(result, fact, base, "description", true);
        if (fact.has("object") && !fact.get("object").isNull()) {
            validateNonBlank(result, fact, base, "object", false);
        }

        JsonNode status = fact.get("status");
        if (status != null) {
            if (!status.isTextual()) {
                result.addError(BAD_TYPE, base + ".status", "status 必须是字符串");
            } else if (status.asText().isEmpty()) {
                result.addError(EMPTY_STRING_IN_ENUM, base + ".status", "状态不得为空串");
            } else if (!statusValues.contains(status.asText())) {
                result.addError(BAD_ENUM, base + ".status", "状态不在允许枚举内");
            }
        }

        JsonNode behaviorDate = fact.get("behaviorDate");
        if (behaviorDate != null) {
            if (!behaviorDate.isTextual()) {
                result.addError(BAD_TYPE, base + ".behaviorDate", "behaviorDate 必须是字符串");
            } else if (!behaviorDatePattern.matcher(behaviorDate.asText()).matches()) {
                result.addError(BAD_FORMAT, base + ".behaviorDate", "日期格式不符合契约");
            }
        }

        JsonNode quantity = fact.get("quantitativeData");
        if (quantity != null && !quantity.isNumber()) {
            result.addError(BAD_TYPE, base + ".quantitativeData", "quantitativeData 必须是数值");
        }
        JsonNode unit = fact.get("quantitativeUnit");
        if (quantity != null && unit == null) {
            result.addError(CROSS_RULE_VIOLATION, base + ".quantitativeUnit", "有定量值时单位必填");
        }
        if (unit != null) {
            validateNonBlank(result, fact, base, "quantitativeUnit", false);
            if (unit.isTextual() && unit.asText().length() > unitMaxLength) {
                result.addError(OUT_OF_RANGE, base + ".quantitativeUnit", "单位长度超过契约上限");
            }
        }

        JsonNode confidence = fact.get("confidence");
        if (confidence != null) {
            if (!confidence.isNumber()) {
                result.addError(BAD_TYPE, base + ".confidence", "confidence 必须是数值");
            } else if (confidence.asDouble() < confidenceMinimum
                    || confidence.asDouble() > confidenceMaximum) {
                result.addError(OUT_OF_RANGE, base + ".confidence", "confidence 超出 [0,1]");
            }
        }

        validateEvidenceIds(result, fact.get("evidenceIds"), base, allowedEvidenceIds);
        for (String field : Arrays.asList("subject", "action", "description")) {
            JsonNode value = fact.get(field);
            if (value != null && value.isTextual() && PROHIBITED_TEXT.matcher(value.asText()).find()) {
                result.addError(PROHIBITED_INFERENCE, base + "." + field,
                        "文本包含风险、法规或合规判断");
            }
        }
    }

    private void validateEvidenceIds(FactExtractionValidationResult result, JsonNode node,
                                     String base, Set<String> allowedEvidenceIds) {
        String field = base + ".evidenceIds";
        if (node == null) {
            return;
        }
        if (!node.isArray()) {
            result.addError(BAD_TYPE, field, "evidenceIds 必须是数组");
            return;
        }
        if (node.size() == 0) {
            result.addError(NULL_REQUIRED_FIELD, field, "evidenceIds 不得为空");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().trim().isEmpty()) {
                result.addError(BAD_TYPE, field, "证据 ID 必须是非空字符串");
                continue;
            }
            if (!seen.add(item.asText())) {
                result.addError(CROSS_RULE_VIOLATION, field, "证据 ID 不得重复");
            }
            if (!allowedEvidenceIds.contains(item.asText())) {
                result.addError(UNKNOWN_REFERENCE, field, "引用输入集合外证据");
            }
        }
    }

    private void validateNonBlank(FactExtractionValidationResult result, JsonNode fact,
                                  String base, String field, boolean required) {
        JsonNode node = fact.get(field);
        if (node == null) {
            return;
        }
        if (node.isNull()) {
            if (required) {
                result.addError(NULL_REQUIRED_FIELD, base + "." + field, "必填字段为 null");
            }
        } else if (!node.isTextual()) {
            result.addError(BAD_TYPE, base + "." + field, "字段必须是字符串");
        } else if (node.asText().trim().isEmpty()) {
            result.addError(NULL_REQUIRED_FIELD, base + "." + field, "字符串为空白");
        }
    }

    private void rejectUnexpected(FactExtractionValidationResult result, JsonNode value,
                                  JsonNode allowedProperties, String prefix) {
        Iterator<String> fields = value.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedProperties.has(field)) {
                result.addError(UNEXPECTED_FIELD, prefix + field, "字段不属于模型输出契约");
                if (PROHIBITED_FIELDS.contains(field)) {
                    result.addError(PROHIBITED_INFERENCE, prefix + field,
                            "禁止输出风险、法规或合规判断字段");
                }
            }
        }
    }

    private ParsedJson parse(String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return new ParsedJson(null, false);
        }
        String text = rawResponse.trim();
        try {
            return new ParsedJson(objectMapper.readTree(text), false);
        } catch (IOException ignored) {
            // 继续识别可解析但违反输出纪律的响应。
        }
        String withoutFence = text.replaceFirst("(?s)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```$", "").trim();
        if (!withoutFence.equals(text)) {
            try {
                return new ParsedJson(objectMapper.readTree(withoutFence), true);
            } catch (IOException ignored) {
                text = withoutFence;
            }
        }
        String objectText = firstCompleteObject(text);
        if (objectText != null) {
            try {
                return new ParsedJson(objectMapper.readTree(objectText), true);
            } catch (IOException ignored) {
                return new ParsedJson(null, false);
            }
        }
        return new ParsedJson(null, false);
    }

    private String firstCompleteObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        return null;
    }

    private Set<String> textSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }

    private static class ParsedJson {
        private final JsonNode node;
        private final boolean extraText;

        private ParsedJson(JsonNode node, boolean extraText) {
            this.node = node;
            this.extraText = extraText;
        }
    }
}
