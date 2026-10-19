package com.riskwarning.common.dto.scoring;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 评分依据的规范化摘要；对象键排序、数值去尾零，数组顺序保持不变。 */
public final class ScoringJson {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static String hash(Object value) {
        try {
            return sha256(MAPPER.writeValueAsString(normalize(MAPPER.valueToTree(value))).getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalArgumentException("评分依据不能计算摘要", failure);
        }
    }

    public static String sha256(byte[] content) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte part : MessageDigest.getInstance("SHA-256").digest(content))
                hex.append(String.format(Locale.ROOT, "%02x", part));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少 SHA-256", failure);
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys);
            for (String key : keys) out.set(key, normalize(node.get(key)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(item -> out.add(normalize(item)));
            return out;
        }
        if (node.isNumber()) {
            if (!Double.isFinite(node.asDouble())) throw new IllegalArgumentException("摘要数值必须有限");
            return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        }
        return node;
    }

    private ScoringJson() { }
}
