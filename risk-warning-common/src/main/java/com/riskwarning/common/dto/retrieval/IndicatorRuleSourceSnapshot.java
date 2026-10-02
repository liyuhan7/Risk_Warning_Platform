package com.riskwarning.common.dto.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** 从实际候选回读冻结规则投影，不携带向量、展示文字或更新时间。 */
public final class IndicatorRuleSourceSnapshot {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonNode capture(String indicatorId, Map<String, ?> source) {
        if (indicatorId == null || indicatorId.trim().isEmpty() || source == null)
            throw new IllegalArgumentException("指标身份与来源不能为空");
        ObjectNode snapshot = MAPPER.createObjectNode(); snapshot.put("indicatorId", indicatorId);
        for (String field : new String[]{"maxScore", "calculationRule", "riskRule"})
            snapshot.set(field, MAPPER.valueToTree(source.get(field)));
        return snapshot;
    }
    private IndicatorRuleSourceSnapshot() { }
}
