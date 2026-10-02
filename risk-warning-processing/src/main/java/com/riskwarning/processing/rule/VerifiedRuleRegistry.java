package com.riskwarning.processing.rule;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.AggregationOperator;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** 随应用部署的只读受控注册表；读取与返回都隔离可变配置对象。 */
@Component
public class VerifiedRuleRegistry {
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private RegistryDocument document;

    @Autowired
    public VerifiedRuleRegistry(@Value("${analysis.rules.location:classpath:rules/verified-rules-v1.json}") String location,
                                @Value("${analysis.rules.allow-test-registry:false}") boolean allowTest) {
        try (InputStream in = new DefaultResourceLoader().getResource(location).getInputStream()) {
            initialize(StreamUtils.copyToString(in, StandardCharsets.UTF_8), allowTest);
        } catch (RuleContractException e) { throw e; }
        catch (Exception e) { throw new RuleContractException("REGISTRY_UNAVAILABLE", "规则资源不可用: " + location); }
    }
    private VerifiedRuleRegistry() { }
    public static VerifiedRuleRegistry fromJson(String json, boolean allowTest) {
        VerifiedRuleRegistry registry = new VerifiedRuleRegistry(); registry.initialize(json, allowTest); return registry;
    }
    private void initialize(String json, boolean allowTest) {
        try { document = mapper.readValue(json, RegistryDocument.class); }
        catch (Exception e) { throw new RuleContractException("INVALID_REGISTRY", "注册表必须为严格 JSON，禁止未知字段、类型强制转换和重复键"); }
        check(document != null && "1.0".equals(document.getSchemaVersion()) && text(document.getRegistryVersion())
                && document.getRules() != null, "INVALID_REGISTRY", "注册表版本或 rules 缺失");
        check("PRODUCTION".equals(document.getPurpose()) || "TEST".equals(document.getPurpose()) && allowTest,
                "TEST_REGISTRY_DISABLED", "测试注册表未获显式启用或 purpose 无效");
        document.getRules().forEach(this::validate);
    }

    /** 当前来源必须来自本 Run 的权威快照；只核对配置里的 hash 字符串不能证明未漂移。 */
    public VerifiedRuleDefinition require(String indicatorId, JsonNode currentSource) {
        VerifiedRuleDefinition rule = document.getRules().get(indicatorId);
        check(rule != null && Boolean.TRUE.equals(rule.getEnabled()), "UNSUPPORTED_RULE", "指标未登记或未启用: " + indicatorId);
        check(currentSource != null && currentSource.isObject(), "SOURCE_RULE_MISSING", "当前规则来源快照缺失");
        check(rule.getSourceRuleHash().equals(RuleCanonicalJson.hash(currentSource)), "SOURCE_RULE_HASH_MISMATCH", "规则来源已漂移: " + indicatorId);
        return mapper.convertValue(rule, VerifiedRuleDefinition.class);
    }
    public boolean isEnabled(String indicatorId) {
        VerifiedRuleDefinition rule = document.getRules().get(indicatorId);
        return rule != null && Boolean.TRUE.equals(rule.getEnabled());
    }
    public String version() { return document.getRegistryVersion(); }

    private void validate(String key, VerifiedRuleDefinition r) {
        check(r != null && key.equals(r.getIndicatorId()) && text(key) && r.getEnabled() != null,
                "INVALID_RULE", "指标键、ID 或 enabled 无效");
        check(r.getSourceRule() != null && r.getSourceRule().isObject() && key.equals(r.getSourceRule().path("indicatorId").asText())
                && r.getSourceRuleHash() != null && r.getSourceRuleHash().matches("[a-f0-9]{64}")
                && r.getSourceRuleHash().equals(RuleCanonicalJson.hash(r.getSourceRule())), "SOURCE_RULE_HASH_MISMATCH", "登记来源 hash 不匹配");
        check(text(r.getRuleVersion()) && text(r.getThresholdVersion()) && r.getRuleType() != null
                && r.getAggregationOperator() != null && finite(r.getMaxScore()) && r.getMaxScore() > 0,
                "INVALID_RULE", "规则版本、类型、聚合算子或最大分无效");
        if (r.getEnabled()) {
            check(text(r.getVerifiedBy()) && text(r.getVerifiedAt()) && text(r.getBasis()), "UNVERIFIED_RULE", "启用规则缺少核验记录");
            try { LocalDateTime.parse(r.getVerifiedAt()); }
            catch (Exception e) { throw new RuleContractException("UNVERIFIED_RULE", "verifiedAt 必须是东八区本地 ISO 时间"); }
        }
        if (r.getRuleType() == RuleTypeEnum.BINARY) {
            check(text(r.getCondition()) && r.getObservation() == null && r.getSegments() == null && r.getInputUnit() == null
                    && Arrays.asList(AggregationOperator.SINGLE, AggregationOperator.ALL, AggregationOperator.ANY, AggregationOperator.LATEST).contains(r.getAggregationOperator()),
                    "INVALID_BINARY", "Binary 条件、聚合或互斥字段无效");
            score(r.getTrueScore(), r.getMaxScore()); score(r.getFalseScore(), r.getMaxScore());
        } else {
            check(text(r.getObservation()) && text(r.getInputUnit()) && r.getCondition() == null && r.getTrueScore() == null && r.getFalseScore() == null
                    && Arrays.asList(AggregationOperator.SINGLE, AggregationOperator.MIN, AggregationOperator.MAX, AggregationOperator.SUM, AggregationOperator.AVG, AggregationOperator.LATEST).contains(r.getAggregationOperator())
                    && r.getSegments() != null && !r.getSegments().isEmpty(), "INVALID_RANGE", "Range 输入、聚合或分段缺失");
            VerifiedRuleDefinition.ScoreSegment previous = null;
            for (VerifiedRuleDefinition.ScoreSegment s : r.getSegments()) {
                check(s != null, "INVALID_RANGE", "分段不能为空");
                bounds(s.getLower(), s.getUpper(), s.getLowerInclusive(), s.getUpperInclusive()); score(s.getScore(), r.getMaxScore());
                if (previous != null) check(Double.compare(previous.getUpper(), s.getLower()) == 0
                        && !previous.getUpperInclusive().equals(s.getLowerInclusive()), "INVALID_RANGE", "分段无序、重叠或域内未覆盖");
                previous = s;
            }
        }
        check(r.getRiskIntervals() != null, "INVALID_THRESHOLD", "比例阈值必须显式登记，允许空数组表示不触发");
        VerifiedRuleDefinition.RiskInterval previous = null;
        for (VerifiedRuleDefinition.RiskInterval b : r.getRiskIntervals()) {
            check(b != null, "INVALID_THRESHOLD", "风险区间不能为空");
            bounds(b.getLower(), b.getUpper(), b.getLowerInclusive(), b.getUpperInclusive());
            check(b.getLower() >= 0 && b.getUpper() <= 1 && b.getRiskLevel() != null && b.getRiskLevel() != RiskLevelEnum.NO_RISK,
                    "INVALID_THRESHOLD", "风险区间须在 [0,1] 且明确风险等级");
            if (previous != null) check(previous.getUpper() < b.getLower()
                    || Double.compare(previous.getUpper(), b.getLower()) == 0 && !(previous.getUpperInclusive() && b.getLowerInclusive()),
                    "INVALID_THRESHOLD", "风险区间无序或重叠");
            previous = b;
        }
    }
    private static void bounds(Double lower, Double upper, Boolean li, Boolean ui) {
        check(finite(lower) && finite(upper) && li != null && ui != null && (lower < upper || lower.equals(upper) && li && ui),
                "INVALID_INTERVAL", "区间边界非法或为空");
    }
    private static void score(Double score, Double max) { check(finite(score) && score >= 0 && score <= max, "INVALID_SCORE", "得分须有限且在 [0,maxScore]"); }
    private static boolean finite(Double value) { return value != null && Double.isFinite(value); }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void check(boolean test, String code, String message) { if (!test) throw new RuleContractException(code, message); }
    @Data
    public static class RegistryDocument {
        private String schemaVersion;
        private String registryVersion;
        private String purpose;
        private Map<String, VerifiedRuleDefinition> rules;
    }
}
