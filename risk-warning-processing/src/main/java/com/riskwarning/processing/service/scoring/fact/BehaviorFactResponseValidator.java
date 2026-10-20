package com.riskwarning.processing.service.scoring.fact;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import java.math.BigDecimal;
import java.util.*;

/** 分项保留通过校验的答案，漏答与无效项交给一次定向修复。 */
public class BehaviorFactResponseValidator {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("questionId", "status", "booleanValue", "numberValue", "unit", "applicability", "evidenceIds", "confidence", "reason"));
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    public Map<String, Answer> validate(String raw, Collection<Question> questions, Set<String> evidence) {
        Map<String, Answer> out = new LinkedHashMap<>(); Map<String, Question> expected = new HashMap<>();
        questions.forEach(question -> expected.put(question.getId(), question));
        JsonNode root;
        try { root = mapper.readTree(raw); }
        catch (Exception invalid) { return out; }
        if (root == null || !root.isObject() || root.size() != 1 || !root.path("answers").isArray()) return out;
        Set<String> seen = new HashSet<>();
        for (JsonNode row : root.get("answers")) {
            String id = row.path("questionId").asText();
            if (!seen.add(id)) { out.remove(id); continue; }
            Question question = expected.get(id);
            if (question == null || !row.isObject()) continue;
            try {
                Iterator<String> names = row.fieldNames();
                while (names.hasNext()) if (!FIELDS.contains(names.next())) throw new IllegalArgumentException("未知字段");
                Answer answer = mapper.treeToValue(row, Answer.class);
                validate(answer, question.getDefinition().getType(), question.getDefinition().getUnit(), evidence);
                out.put(id, answer);
            } catch (Exception invalid) { out.remove(id); }
        }
        return out;
    }

    private void validate(Answer answer, String type, String unit, Set<String> evidence) {
        require(Arrays.asList("KNOWN", "NOT_STATED", "UNCERTAIN").contains(answer.getStatus()));
        require(answer.getEvidenceIds() != null && evidence.containsAll(answer.getEvidenceIds())
                && new HashSet<>(answer.getEvidenceIds()).size() == answer.getEvidenceIds().size());
        require(answer.getConfidence() == null || answer.getConfidence().compareTo(BigDecimal.ZERO) >= 0 && answer.getConfidence().compareTo(BigDecimal.ONE) <= 0);
        if (!"KNOWN".equals(answer.getStatus())) {
            require(answer.getBooleanValue() == null && answer.getNumberValue() == null && answer.getUnit() == null
                    && (answer.getApplicability() == null || "APPLICABILITY".equals(type) && "UNKNOWN".equals(answer.getApplicability())));
            return;
        }
        require(!answer.getEvidenceIds().isEmpty());
        if ("BOOLEAN".equals(type)) require(answer.getBooleanValue() != null && answer.getNumberValue() == null && answer.getUnit() == null && answer.getApplicability() == null);
        else if ("NUMBER".equals(type)) require(answer.getNumberValue() != null && Objects.equals(unit, answer.getUnit()) && answer.getBooleanValue() == null && answer.getApplicability() == null);
        else require("APPLICABILITY".equals(type) && Arrays.asList("APPLICABLE", "NOT_APPLICABLE").contains(answer.getApplicability())
                    && answer.getBooleanValue() == null && answer.getNumberValue() == null && answer.getUnit() == null);
    }

    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("事实答案契约无效"); }
}
