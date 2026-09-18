package com.riskwarning.common.dto.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.riskwarning.common.po.risk.RelatedBehavior;
import com.riskwarning.common.po.risk.RelatedIndicator;
import com.riskwarning.common.po.risk.RelatedRegulation;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冻结 calculationDetails（IndicatorResultDetail）的 JSON 键名。
 *
 * 该结构直接写入 t_indicator_result.calculation_details，被 Report 汇总与前端展示消费；
 * 字段名一旦变化就是破坏性变更，因此用测试钉死键名，而不是只靠文档约定。
 */
class IndicatorResultDetailContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void topLevelKeysAreFrozen() throws Exception {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(sample()));

        assertEquals("1.0", json.get("schemaVersion").asText());
        assertEquals("MOCK_FIXTURE", json.get("analysisMode").asText());
        assertTrue(json.has("fixture"), "fixture 键缺失");
        assertTrue(json.has("relatedIndicators"), "relatedIndicators 键缺失");
        assertTrue(json.has("traces"), "traces 键缺失");
    }

    @Test
    void fixtureAndTraceKeysAreFrozen() throws Exception {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(sample()));

        JsonNode fixture = json.get("fixture");
        assertEquals("CASE-001", fixture.get("fixtureId").asText());
        assertEquals("mock-analysis-v1", fixture.get("fixtureVersion").asText());
        assertTrue(fixture.has("sourceFileSha256"));
        assertTrue(fixture.has("fixtureSha256"));

        JsonNode trace = json.get("traces").get(0);
        assertEquals("behavior-1", trace.get("behaviorId").asText());
        assertTrue(trace.has("evidenceIds"));
        assertTrue(trace.has("queryText"));
        assertTrue(trace.has("queryTemplateVersion"));
        assertTrue(trace.has("filterVersion"));
        assertTrue(trace.has("filterEnabled"));
        assertTrue(trace.has("candidates"));

        JsonNode rule = trace.get("ruleEvaluation");
        assertEquals("p2_mock_fixture_v1", rule.get("ruleVersion").asText());
        assertEquals("RISK", rule.get("decision").asText());
        assertEquals(2.0, rule.get("maxPossibleScore").asDouble());
        assertEquals("MEDIUM_RISK", rule.get("riskLevel").asText());
    }

    @Test
    void relatedIndicatorBooleanKeyIsFrozen() throws Exception {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(sample()));
        JsonNode related = json.get("relatedIndicators").get(0);

        // Lombok 的 boolean isPrimaryTrigger 经 Jackson 默认命名输出为 primaryTrigger，
        // 前端 types/report.ts 里的 isPrimaryTrigger 读不到该字段。
        assertEquals(
                "primaryTrigger",
                booleanKey(related),
                "RelatedIndicator boolean key changed. actual keys: " + keys(related));
    }

    private static String booleanKey(JsonNode related) {
        Iterator<String> names = related.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (related.get(name).isBoolean()) {
                return name;
            }
        }
        return "<none>";
    }

    private static String keys(JsonNode node) {
        return StreamSupport.stream(((Iterable<String>) () -> node.fieldNames()).spliterator(), false)
                .collect(Collectors.joining(", "));
    }

    private static IndicatorResultDetail sample() {
        RelatedRegulation regulation = RelatedRegulation.builder()
                .regulationId("reg-1").regulationName("法规").violationType("")
                .complianceRequirement("要求").build();
        RelatedBehavior behavior = RelatedBehavior.builder()
                .projectId(7L).description("行为").relatedRegulations(Collections.singletonList(regulation)).build();
        RelatedIndicator indicator = RelatedIndicator.builder()
                .indicatorId("ind-1").indicatorName("指标").score(1.0).maxScore(2.0)
                .relatedBehaviors(Collections.singletonList(behavior)).isPrimaryTrigger(true).build();
        RuleEvaluation rule = RuleEvaluation.builder()
                .ruleVersion("p2_mock_fixture_v1").decision("RISK").calculatedScore(0.0)
                .maxPossibleScore(2.0).riskTriggered(true).riskLevel(RiskLevelEnum.MEDIUM_RISK).build();
        AnalysisTrace trace = AnalysisTrace.builder()
                .behaviorId("behavior-1").evidenceIds(Collections.singletonList("evidence-1"))
                .queryText("查询").queryTemplateVersion("query-template-v1").filterVersion("filter-v1")
                .filterEnabled(false).candidates(Collections.emptyList()).ruleEvaluation(rule).build();
        return IndicatorResultDetail.builder()
                .schemaVersion("1.0").analysisMode("MOCK_FIXTURE")
                .fixture(FixtureDescriptor.builder().fixtureId("CASE-001").fixtureVersion("mock-analysis-v1")
                        .sourceFileSha256("source-sha").fixtureSha256("fixture-sha").build())
                .relatedIndicators(Collections.singletonList(indicator))
                .traces(Collections.singletonList(trace)).build();
    }
}
