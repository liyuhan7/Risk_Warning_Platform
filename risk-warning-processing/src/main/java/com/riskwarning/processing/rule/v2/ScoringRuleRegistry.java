package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.processing.rule.RuleContractException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** 严格解析 v2 注册表，加载时校验全部规则，执行时校验本次完整依据。 */
@Component
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class ScoringRuleRegistry {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final ScoringRuleSet document;
    private String contentHash;

    /**
     * 存在静态工厂用的私有构造函数，必须显式标注注入构造函数，
     * 否则 Spring 会回退到不存在的无参构造并报 NoSuchMethodException。
     */
    @Autowired
    public ScoringRuleRegistry(@Value("${analysis.rules.v2-location:classpath:rules/verified-rules-v2.json}") String location,
                               @Value("${analysis.rules.allow-test-registry:false}") boolean allowTest) {
        try (InputStream input = new DefaultResourceLoader().getResource(location).getInputStream()) {
            document = parse(org.springframework.util.StreamUtils.copyToString(input, java.nio.charset.StandardCharsets.UTF_8), allowTest);
        } catch (RuleContractException failure) { throw failure; }
        catch (Exception failure) { throw error("REGISTRY_UNAVAILABLE", "v2 规则资源不可用"); }
    }

    private ScoringRuleRegistry(String json, boolean allowTest, int ignored) { document = parse(json, allowTest); }

    public static ScoringRuleRegistry fromJson(String json, boolean allowTest) {
        return new ScoringRuleRegistry(json, allowTest, 0);
    }

    public String version() { return document.getRegistryVersion(); }
    public String contentHash() { return contentHash; }
    public boolean isEnabled(String id) {
        IndicatorRule rule = document.getRules().get(id);
        return rule != null && Boolean.TRUE.equals(rule.getEnabled());
    }
    public Set<String> indicatorIds() { return Collections.unmodifiableSet(new TreeSet<>(document.getRules().keySet())); }

    public IndicatorRule definition(String id) {
        check(isEnabled(id), "UNSUPPORTED_RULE", "指标未登记或未启用");
        return registeredDefinition(id);
    }

    public IndicatorRule registeredDefinition(String id) {
        check(document.getRules().containsKey(id), "UNSUPPORTED_RULE", "指标未登记");
        return mapper.convertValue(document.getRules().get(id), IndicatorRule.class);
    }

    /** 摘要与每条要求的关系编号均须对应，不能仅信任注册表自带的摘要。 */
    public IndicatorRule require(String id, ScoringSourceBundle bundle) {
        IndicatorRule rule = definition(id);
        check(bundle != null && bundle.getIndicator() != null && id.equals(bundle.getIndicator().path("id").asText())
                && text(bundle.getDatasetVersion()) && bundle.getRelations() != null,
                "SOURCE_BUNDLE_MISSING", "评分依据缺失或指标不一致");
        check(rule.getSourceBundleHash().equals(bundle.bundleHash()), "SOURCE_BUNDLE_HASH_MISMATCH", "评分依据版本或内容发生变化");
        Map<String, ScoringSourceBundle.Source> sources = new HashMap<>();
        for (ScoringSourceBundle.Source source : bundle.getRelations()) {
            check(source != null && text(source.getRegulationId()) && source.getRegulation() != null
                    && source.getRelation() != null && sources.put(source.getRegulationId(), source) == null,
                    "SOURCE_BUNDLE_MISSING", "法规依据缺失或重复");
            check(id.equals(source.getRelation().path("indicatorId").asText())
                    && source.getRegulationId().equals(source.getRelation().path("regulationId").asText())
                    && source.getRegulationId().equals(source.getRegulation().path("id").asText())
                    && ScoringJson.hash(source.getRelation()).equals(source.getRelationHash()),
                    "SOURCE_BUNDLE_HASH_MISMATCH", "关系身份或内容不一致");
            check(ScoringJson.sha256(source.getRegulation().path("fullText").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .equals(source.getFullTextHash()) && ScoringJson.hash(source.getReference()).equals(source.getReferenceSourcesHash()),
                    "SOURCE_BUNDLE_HASH_MISMATCH", "法规原文或来源摘要不一致");
        }
        for (Requirement requirement : rule.getRequirements()) {
            ScoringSourceBundle.Source source = sources.get(requirement.getSourceRegulationId());
            check(source != null && requirement.getSourceRelationHash().equals(source.getRelationHash()),
                    "SOURCE_BUNDLE_MISSING", "登记要求的法规或关系依据缺失");
        }
        return rule;
    }

    private ScoringRuleSet parse(String json, boolean allowTest) {
        ScoringRuleSet rules;
        try {
            JsonNode tree = mapper.readTree(json);
            contentHash = ScoringJson.sha256(json.replace("\r\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            validateRiskNames(tree);
            rules = mapper.treeToValue(tree, ScoringRuleSet.class);
        }
        catch (Exception failure) { throw error("INVALID_REGISTRY", "v2 注册表必须为严格 JSON，禁止未知字段和隐式类型转换"); }
        check(rules != null && "2.0".equals(rules.getSchemaVersion()) && text(rules.getRegistryVersion()) && rules.getRules() != null,
                "INVALID_REGISTRY", "v2 注册表版本或内容缺失");
        check("PRODUCTION".equals(rules.getPurpose()) || allowTest && "TEST".equals(rules.getPurpose()),
                "TEST_REGISTRY_DISABLED", "测试注册表没有显式启用");
        Set<String> requirementIds = new HashSet<>();
        rules.getRules().forEach((id, rule) -> validate(id, rule, requirementIds));
        return rules;
    }

    private void validate(String id, IndicatorRule rule, Set<String> requirementIds) {
        valid(text(id) && rule != null && rule.getEnabled() != null, "指标身份或启用状态无效");
        valid(text(rule.getRuleVersion()) && digest(rule.getSourceBundleHash()) && rule.getMaxScore() != null
                && rule.getMaxScore().compareTo(BigDecimal.valueOf(100)) == 0 && text(rule.getThresholdVersion()), "版本、来源摘要或百分制满分无效");
        if (rule.getEnabled()) {
            valid(text(rule.getVerifiedBy()) && text(rule.getBasis()) && text(rule.getVerifiedAt()), "启用规则必须有人工作业依据和审核记录");
            try { OffsetDateTime.parse(rule.getVerifiedAt()); }
            catch (Exception failure) { throw error("INVALID_REGISTRY", "核验时间必须包含时区"); }
        }
        valid(rule.getRequirements() != null && !rule.getRequirements().isEmpty(), "指标要求不能为空");
        boolean expressible = false;
        for (Requirement requirement : rule.getRequirements()) {
            valid(requirement != null && text(requirement.getRequirementId()) && requirementIds.add(requirement.getRequirementId()), "要求编号缺失或跨指标重复");
            valid(text(requirement.getSourceRegulationId()) && digest(requirement.getSourceRelationHash()) && requirement.getExpressible() != null,
                    "要求缺少来源或可表达状态");
            if (!requirement.getExpressible()) { valid(text(requirement.getReviewReason()), "不可表达要求必须说明待审原因"); continue; }
            expressible = true;
            valid(requirement.getDirection() != null && requirement.getValueType() != null && requirement.getSeverity() != null
                    && requirement.getWeight() != null && requirement.getWeight().signum() > 0, "要求类型、严重度或权重无效");
            valid(requirement.getApplicabilityGate() != null && "MODEL_JUDGED".equals(requirement.getApplicabilityGate().getMode())
                    && text(requirement.getApplicabilityGate().getText()), "一期要求必须有明确适用性判断门");
            Set<String> conditions = new HashSet<>();
            condition(requirement.getCondition(), requirement.getValueType(), conditions, 1);
            if (requirement.getExceptWhen() != null) condition(requirement.getExceptWhen(), requirement.getValueType(), conditions, 1);
            Aggregation op = requirement.getCrossBehaviorAggregation();
            valid(op != null, "必须登记跨行为聚合方式");
            if (requirement.getDirection() == Direction.FORBIDDEN) {
                valid(requirement.getValueType() == ValueType.BOOLEAN ? op == Aggregation.ANY_VIOLATION || op == Aggregation.SINGLE
                        : Arrays.asList(Aggregation.MIN, Aggregation.MAX, Aggregation.LATEST, Aggregation.SINGLE).contains(op), "禁止类聚合不得稀释违规");
            } else if (requirement.getValueType() == ValueType.BOOLEAN) {
                valid(Arrays.asList(Aggregation.SINGLE, Aggregation.ALL, Aggregation.ANY, Aggregation.EXISTS, Aggregation.LATEST).contains(op), "布尔聚合方式无效");
            }
            if (requirement.getValueType() == ValueType.NUMBER)
                valid(requirement.getCondition().getOperator() != null
                        && (requirement.getCondition().getValueType() == null || requirement.getCondition().getValueType() == ValueType.NUMBER)
                        && Arrays.asList(Aggregation.SINGLE, Aggregation.MIN, Aggregation.MAX, Aggregation.SUM, Aggregation.AVG, Aggregation.LATEST).contains(op),
                        "数值聚合要求独立数值条件和数值算子");
            if (requirement.getMinimumRiskLevel() != null)
                valid(requirement.getDirection() == Direction.FORBIDDEN && requirement.getMinimumRiskLevel() != RiskLevelEnum.NO_RISK,
                        "只有禁止类可登记有效风险底线");
            grading(requirement);
        }
        valid(!rule.getEnabled() || expressible, "启用指标至少需要一项可执行要求");
        intervals(rule.getRiskIntervals());
    }

    private void condition(Condition node, ValueType inherited, Set<String> ids, int depth) {
        valid(node != null && depth <= 3, "条件缺失或超过三层");
        int branches = (node.getAllOf() == null ? 0 : 1) + (node.getAnyOf() == null ? 0 : 1) + (node.getNotOf() == null ? 0 : 1);
        if (branches > 0) {
            valid(branches == 1 && node.getOperator() == null && node.getConditionId() == null && node.getValueType() == null
                    && node.getOperand() == null && node.getUpperOperand() == null && node.getUnit() == null && node.getPromptHint() == null
                    && node.getLowerInclusive() == null && node.getUpperInclusive() == null, "组合条件不得混用叶子字段");
            if (node.getNotOf() != null) condition(node.getNotOf(), inherited, ids, depth + 1);
            else {
                List<Condition> children = node.getAllOf() != null ? node.getAllOf() : node.getAnyOf();
                valid(!children.isEmpty(), "组合条件不能为空");
                for (Condition child : children) condition(child, inherited, ids, depth + 1);
            }
            return;
        }
        valid(text(node.getConditionId()) && ids.add(node.getConditionId()) && text(node.getPromptHint()) && node.getOperator() != null, "条件编号、提示或运算符缺失");
        ValueType type = node.getValueType() == null ? inherited : node.getValueType();
        if (type == ValueType.BOOLEAN) {
            valid((node.getOperator() == Operator.IS_TRUE || node.getOperator() == Operator.IS_FALSE)
                    && node.getOperand() == null && node.getUpperOperand() == null && node.getUnit() == null
                    && node.getLowerInclusive() == null && node.getUpperInclusive() == null, "布尔条件字段无效");
        } else {
            valid(node.getOperator() != Operator.IS_TRUE && node.getOperator() != Operator.IS_FALSE && node.getOperand() != null
                    && text(node.getUnit()), "数值条件缺少阈值或单位");
            if (node.getOperator() == Operator.BETWEEN)
                valid(node.getUpperOperand() != null && node.getOperand().compareTo(node.getUpperOperand()) < 0
                        && node.getLowerInclusive() != null && node.getUpperInclusive() != null, "区间条件边界无效");
            else valid(node.getUpperOperand() == null && node.getLowerInclusive() == null && node.getUpperInclusive() == null, "比较条件混用区间字段");
        }
    }

    private void grading(Requirement requirement) {
        Grading grading = requirement.getGrading();
        valid(grading != null && grading.getMode() != null, "计分方式缺失");
        if (grading.getMode() == GradingMode.BINARY) {
            valid(ONE.equals(grading.getSatisfied() == null ? null : grading.getSatisfied().stripTrailingZeros())
                    && fraction(grading.getViolated()) && grading.getSegments() == null, "二元满足度或互斥字段无效");
            valid(requirement.getDirection() == Direction.OPTIONAL || grading.getViolated().compareTo(ONE) < 0,
                    "非可选违规不得得到完全满足度");
        } else {
            valid(requirement.getValueType() == ValueType.NUMBER && requirement.getCondition().getOperator() != null
                    && grading.getSatisfied() == null && grading.getViolated() == null && grading.getSegments() != null
                    && !grading.getSegments().isEmpty(), "分段只支持独立数值条件且不得混用二元分数");
            Segment previous = null;
            for (int i = 0; i < grading.getSegments().size(); i++) {
                Segment segment = grading.getSegments().get(i);
                valid(segment != null && fraction(segment.getSatisfaction()) && segment.getLowerInclusive() != null
                        && segment.getUpperInclusive() != null, "分段满足度或开闭边界缺失");
                valid((segment.getLower() != null || i == 0 && !segment.getLowerInclusive())
                        && (segment.getUpper() != null || i == grading.getSegments().size() - 1 && !segment.getUpperInclusive()), "无界只能用于首尾开放边界");
                valid(segment.getLower() == null || segment.getUpper() == null || segment.getLower().compareTo(segment.getUpper()) < 0, "分段上下界倒置");
                if (previous != null)
                    valid(previous.getUpper() != null && segment.getLower() != null && previous.getUpper().compareTo(segment.getLower()) == 0
                            && previous.getUpperInclusive() != segment.getLowerInclusive(), "分段无序、重叠或存在缺口");
                validateSegmentMeaning(requirement, segment);
                previous = segment;
            }
        }
    }

    private void validateSegmentMeaning(Requirement requirement, Segment segment) {
        List<BigDecimal> points = new ArrayList<>();
        Condition condition = requirement.getCondition();
        if (segment.getLower() != null) points.add(segment.getLower());
        if (segment.getUpper() != null) points.add(segment.getUpper());
        points.add(condition.getOperand());
        if (condition.getUpperOperand() != null) points.add(condition.getUpperOperand());
        Collections.sort(points);
        List<BigDecimal> probes = new ArrayList<>(points);
        probes.add(points.get(0).subtract(ONE));
        probes.add(points.get(points.size() - 1).add(ONE));
        for (int i = 1; i < points.size(); i++) probes.add(points.get(i - 1).add(points.get(i)).divide(BigDecimal.valueOf(2)));
        for (BigDecimal probe : probes) {
            boolean inside = (segment.getLower() == null || probe.compareTo(segment.getLower()) > 0
                    || segment.getLowerInclusive() && probe.compareTo(segment.getLower()) == 0)
                    && (segment.getUpper() == null || probe.compareTo(segment.getUpper()) < 0
                    || segment.getUpperInclusive() && probe.compareTo(segment.getUpper()) == 0);
            if (!inside) continue;
            boolean satisfies = UniversalConditionEvaluator.number(condition, probe);
            valid(satisfies ? segment.getSatisfaction().compareTo(ONE) == 0
                    : requirement.getDirection() == Direction.OPTIONAL || segment.getSatisfaction().compareTo(ONE) < 0,
                    "分段满足度与条件边界不一致，必须拆分跨越合规边界的分段");
        }
    }

    private void validateRiskNames(JsonNode node) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if (("riskLevel".equals(entry.getKey()) || "minimumRiskLevel".equals(entry.getKey())) && !entry.getValue().isNull())
                valid(entry.getValue().isTextual() && Arrays.stream(RiskLevelEnum.values()).anyMatch(level -> level.name().equals(entry.getValue().asText())), "风险等级必须为已定义枚举");
            validateRiskNames(entry.getValue());
        });
        else if (node.isArray()) node.forEach(this::validateRiskNames);
    }

    private void intervals(List<RiskInterval> intervals) {
        valid(intervals != null, "必须显式登记风险区间，允许空数组");
        RiskInterval previous = null;
        for (RiskInterval interval : intervals) {
            valid(interval != null && fraction(interval.getLower()) && fraction(interval.getUpper()) && interval.getRiskLevel() != null
                    && interval.getLowerInclusive() != null && interval.getUpperInclusive() != null
                    && interval.getLower().compareTo(interval.getUpper()) < 0, "风险区间无效");
            if (previous != null)
                valid(previous.getUpper().compareTo(interval.getLower()) < 0 || previous.getUpper().compareTo(interval.getLower()) == 0
                        && !(previous.getUpperInclusive() && interval.getLowerInclusive()), "风险区间无序或重叠");
            previous = interval;
        }
    }

    private static boolean fraction(BigDecimal n) { return n != null && n.signum() >= 0 && n.compareTo(ONE) <= 0; }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void valid(boolean condition, String message) { check(condition, "INVALID_REGISTRY", message); }
    private static void check(boolean valid, String code, String message) { if (!valid) throw error(code, message); }
    private static RuleContractException error(String code, String message) { return new RuleContractException(code, message); }
}
