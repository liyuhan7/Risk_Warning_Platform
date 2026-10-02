package com.riskwarning.processing.analysis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.riskwarning.processing.dto.analysis.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** 严格解析单 JSON 对象，校验当前事实的引用与规则桥接输入。 */
@Component
public class ComplianceResponseValidator {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("schemaVersion", "indicatorId",
            "evidenceIds", "regulationIds", "applicable", "requirement", "enterpriseFact", "complianceStatus",
            "gapType", "gapValue", "gapUnit", "conditionMet", "observedValue", "observedUnit", "confidence", "reasoning"));

    public ComplianceValidation validate(String raw, BehaviorAnalysisContextVO context,
                                          Map<String, RuleInputRequirement> requirements) {
        List<String> errors = new ArrayList<>();
        JsonNode node;
        try {
            node = raw == null ? null : mapper.readTree(raw);
            if (node == null || !node.isObject()) return invalid("RESPONSE: expected single JSON object");
        } catch (Exception e) { return invalid("RESPONSE: invalid JSON, duplicate key or trailing content"); }
        node.fieldNames().forEachRemaining(k -> { if (!FIELDS.contains(k)) errors.add(k + ": unknown field"); });
        if (!node.path("schemaVersion").isTextual() || !"1.1".equals(node.path("schemaVersion").asText())) errors.add("schemaVersion: expected string 1.1");
        if (!node.has("indicatorId")) errors.add("indicatorId: required (null when no applicable candidate)");
        text(node, "enterpriseFact", true, errors);
        text(node, "reasoning", true, errors);
        finite(node, "confidence", true, errors);
        if (node.path("confidence").isNumber() && (node.get("confidence").asDouble() < 0 || node.get("confidence").asDouble() > 1))
            errors.add("confidence: expected [0,1]");
        references(node, "evidenceIds", new HashSet<>(context.getEvidenceIds()), true, errors);
        Set<String> regulations = new HashSet<>();
        if (context.getRegulationCandidates() != null) for (RetrievalCandidateVO candidate : context.getRegulationCandidates()) {
            if (candidate.isSnapshotAvailable() && candidate.isContentAvailable() && candidate.getContent() != null
                    && !candidate.getContent().trim().isEmpty()) regulations.add(candidate.getCandidateId());
        }
        references(node, "regulationIds", regulations, false, errors);
        if (node.path("regulationIds").size() == 0) {
            for (String key : Arrays.asList("reasoning", "requirement", "enterpriseFact")) {
                if (node.path(key).asText().matches("(?is).*?(违法|违规|illegal|unlawful).*"))
                    errors.add(key + ": legal claim terminology requires regulation reference");
            }
        }
        String status = node.path("complianceStatus").asText();
        String applicable = node.path("applicable").asText();
        boolean noIndicator = !node.hasNonNull("indicatorId");
        if (noIndicator) {
            if (!"NO_APPLICABLE_CANDIDATE".equals(status) || !"NOT_APPLICABLE".equals(applicable))
                errors.add("indicatorId: null requires NOT_APPLICABLE/NO_APPLICABLE_CANDIDATE");
        } else {
            text(node, "indicatorId", true, errors);
            if (!ids(context.getIndicatorCandidates()).contains(node.path("indicatorId").asText())) errors.add("indicatorId: outside current candidates");
            if (!Arrays.asList("COMPLIANT", "NON_COMPLIANT", "INSUFFICIENT_EVIDENCE", "NEEDS_REVIEW").contains(status))
                errors.add("complianceStatus: unsupported status");
        }
        if (!Arrays.asList("APPLICABLE", "NOT_APPLICABLE", "UNKNOWN").contains(applicable)) errors.add("applicable: unsupported value");
        if ("UNKNOWN".equals(applicable) && !Arrays.asList("INSUFFICIENT_EVIDENCE", "NEEDS_REVIEW").contains(status))
            errors.add("applicable: UNKNOWN requires undecided status");
        if ("NOT_APPLICABLE".equals(applicable) && "NON_COMPLIANT".equals(status)) errors.add("complianceStatus: not applicable cannot be non-compliant");
        // 无法规引用时保守拒绝明确不合规标签，不靠说明文字猜测法律依据。
        if ("NON_COMPLIANT".equals(status) && (!node.path("regulationIds").isArray() || node.path("regulationIds").size() == 0))
            errors.add("regulationIds: NON_COMPLIANT requires referenced regulation");
        if (node.hasNonNull("gapType") && !Arrays.asList("MISSING_ACTION", "PROHIBITED_ACTION", "QUANTITATIVE_SHORTFALL",
                "QUANTITATIVE_EXCESS", "DOCUMENTATION_GAP").contains(node.path("gapType").asText())) errors.add("gapType: unsupported value");
        if ("NON_COMPLIANT".equals(status)) text(node, "gapType", true, errors);
        for (String k : Arrays.asList("requirement", "gapUnit", "observedUnit")) text(node, k, false, errors);
        finite(node, "gapValue", false, errors);
        finite(node, "observedValue", false, errors);
        if (node.hasNonNull("gapValue")) text(node, "gapUnit", true, errors);
        if (node.hasNonNull("observedValue")) text(node, "observedUnit", true, errors);
        if (node.hasNonNull("observedUnit") && !node.hasNonNull("observedValue")) errors.add("observedValue: required with unit");
        if (node.hasNonNull("conditionMet") && !node.get("conditionMet").isBoolean()) errors.add("conditionMet: expected boolean");
        if (node.hasNonNull("conditionMet") && (node.hasNonNull("observedValue") || node.hasNonNull("observedUnit")))
            errors.add("conditionMet: Binary and Range inputs are mutually exclusive");
        RuleInputRequirement rule = requirements.get(node.path("indicatorId").asText());
        boolean decided = "APPLICABLE".equals(applicable) && Arrays.asList("COMPLIANT", "NON_COMPLIANT").contains(status);
        if (rule == null) {
            if (node.hasNonNull("conditionMet") || node.hasNonNull("observedValue") || node.hasNonNull("observedUnit"))
                errors.add("ruleInput: no controlled requirement for selected indicator");
        } else if ("BINARY".equals(rule.getRuleType())) {
            if (node.hasNonNull("observedValue") || node.hasNonNull("observedUnit")) errors.add("ruleInput: BINARY forbids Range fields");
            if (decided && !node.hasNonNull("conditionMet")) errors.add("conditionMet: required for BINARY decision");
        } else if ("RANGE".equals(rule.getRuleType())) {
            if (node.hasNonNull("conditionMet")) errors.add("ruleInput: RANGE forbids Binary field");
            if (decided && !node.hasNonNull("observedValue")) errors.add("observedValue: required for RANGE decision");
            if (node.hasNonNull("observedUnit") && !Objects.equals(rule.getInputUnit(), node.path("observedUnit").asText()))
                errors.add("observedUnit: expected controlled unit " + rule.getInputUnit());
        }
        if ((noIndicator || !decided) && (node.hasNonNull("conditionMet") || node.hasNonNull("observedValue") || node.hasNonNull("observedUnit")))
            errors.add("ruleInput: undecided or not applicable response must not supply calculation inputs");
        return new ComplianceValidation(node, Collections.unmodifiableList(errors));
    }

    private static ComplianceValidation invalid(String error) { return new ComplianceValidation(null, Collections.singletonList(error)); }
    private static void text(JsonNode n, String key, boolean required, List<String> e) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) { if (required) e.add(key + ": required"); }
        else if (!v.isTextual() || v.asText().trim().isEmpty()) e.add(key + ": expected nonblank string");
    }
    private static void finite(JsonNode n, String key, boolean required, List<String> e) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) { if (required) e.add(key + ": required"); }
        else if (!v.isNumber() || !Double.isFinite(v.asDouble())) e.add(key + ": expected finite number");
    }
    private static void references(JsonNode n, String key, Set<String> allowed, boolean nonempty, List<String> e) {
        JsonNode v = n.get(key);
        if (v == null || !v.isArray()) { e.add(key + ": expected array"); return; }
        if (nonempty && v.size() == 0) e.add(key + ": must not be empty");
        Set<String> seen = new HashSet<>();
        for (JsonNode id : v) if (!id.isTextual() || !allowed.contains(id.asText()) || !seen.add(id.asText()))
            e.add(key + ": unknown or duplicate reference " + id.toString());
    }
    private static Set<String> ids(List<RetrievalCandidateVO> candidates) {
        Set<String> ids = new HashSet<>();
        if (candidates != null) for (RetrievalCandidateVO candidate : candidates) {
            if (candidate.isSnapshotAvailable()) ids.add(candidate.getCandidateId());
        }
        return ids;
    }
}
