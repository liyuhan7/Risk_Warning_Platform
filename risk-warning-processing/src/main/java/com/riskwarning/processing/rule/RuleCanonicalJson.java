package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import java.util.*;

/** 对象键排序、数值规范化，数组顺序保留；hash 不依赖 JSON 排版。 */
public final class RuleCanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public static String hash(Object value) {
        try { return EvidenceChunk.sha256(MAPPER.writeValueAsString(normalize(MAPPER.valueToTree(value)))); }
        catch (Exception e) { throw new RuleContractException("INVALID_HASH_INPUT", "规则 hash 输入不可序列化"); }
    }
    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode(); List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
            for (String key : keys) out.set(key, normalize(node.get(key)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode(); node.forEach(v -> out.add(normalize(v))); return out;
        }
        if (node.isNumber()) {
            if (!Double.isFinite(node.asDouble())) throw new RuleContractException("INVALID_HASH_INPUT", "hash 数值必须有限");
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        return node;
    }
    private RuleCanonicalJson() { }
}
