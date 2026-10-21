package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.RequirementFinding.ConditionValue;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.enums.analysis.*;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 当前业务规则的事实、计分和跨行为聚合回归。 */
class BusinessCatalogRulesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScoringRuleRegistry registry = new ScoringRuleRegistry("classpath:rules/business-catalog-test-v2.json", true);
    private final ScoringDataset dataset;
    private final JsonNode cases;

    BusinessCatalogRulesTest() throws Exception {
        Path data = Files.exists(Paths.get("documents/data")) ? Paths.get("documents/data") : Paths.get("../documents/data");
        dataset = new ScoringDataset(data.toString());
        try (java.io.InputStream input = getClass().getResourceAsStream("/scoring/catalog-business-cases.json")) {
            assertNotNull(input, "累计业务案例必须随测试交付");
            cases = mapper.readTree(input);
        }
    }

    @Test void allRegisteredIndicatorsMatchCurrentSources() {
        assertEquals(650, registry.indicatorIds().size());
        int requirements = 0;
        for (String id : registry.indicatorIds()) {
            IndicatorRule current = registry.require(id, bundle(id));
            assertTrue(current.getEnabled(), id);
            assertTrue(current.getRequirements().stream().allMatch(Requirement::getExpressible));
            requirements += current.getRequirements().size();
        }
        assertEquals(1362, requirements);
    }

    @Test void scenarioIndicatorsHaveSatisfiedViolatedAndPendingCases() {
        Map<String, Set<String>> coverage = new HashMap<>();
        Set<String> names = new HashSet<>();
        assertTrue(cases.isArray());
        for (JsonNode item : cases) {
            String id = item.path("indicatorId").asText();
            assertTrue(registry.isEnabled(id), "案例必须对应当前已启用的指标: " + id);
            assertTrue(item.path("behavior").asText().length() >= 10, "案例须有具体业务叙述");
            assertTrue(names.add(id + ":" + item.path("name").asText()), "案例名称重复");
            Set<String> covered = coverage.computeIfAbsent(id, ignored -> new HashSet<>());
            if (!item.path("pendingRequirementIds").isEmpty()) covered.add("pending");
            else if (!item.path("violatedRequirementIds").isEmpty()) covered.add("violated");
            else covered.add("satisfied");
        }
        assertEquals(610, coverage.size(), "业务案例应覆盖610个指标，其余劳务规则由专门场景验证");
        coverage.forEach((id, types) -> assertEquals(new HashSet<>(Arrays.asList("satisfied", "violated", "pending")), types, id));
    }

    @TestFactory Stream<DynamicTest> businessCases() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode item : cases)
            tests.add(DynamicTest.dynamicTest(item.path("name").asText() + " [" + item.path("indicatorId").asText() + "]",
                    () -> assertDecision(item, evaluate(item, "behavior-1", null))));
        return tests.stream();
    }

    @TestFactory Stream<DynamicTest> prohibitedViolationsAreNotDilutedByCompliantBehaviors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode violated : cases) {
            String id = violated.path("indicatorId").asText();
            if (!violated.path("pendingRequirementIds").isEmpty() || violated.path("violatedRequirementIds").isEmpty()) continue;
            Set<String> ids = ids(violated, "violatedRequirementIds");
            if (registry.definition(id).getRequirements().stream()
                    .noneMatch(r -> ids.contains(r.getRequirementId()) && r.getDirection() == Direction.FORBIDDEN)) continue;
            JsonNode satisfied = null;
            for (JsonNode candidate : cases)
                if (id.equals(candidate.path("indicatorId").asText()) && candidate.path("pendingRequirementIds").isEmpty()
                        && candidate.path("violatedRequirementIds").isEmpty()
                        && ids(candidate, "notApplicableRequirementIds").equals(ids(violated, "notApplicableRequirementIds"))) { satisfied = candidate; break; }
            if (satisfied == null) continue; // 不把不同适用分母的行为分数混为同一比较。
            final JsonNode compliant = satisfied;
            tests.add(DynamicTest.dynamicTest("禁止违规不被合规行为摊薄 [" + id + "]", () -> {
                ScoringDecision single = evaluate(violated, "violation", null);
                ScoringDecision combined = evaluate(violated, "violation", compliant);
                assertEquals(0, single.getCalculatedScore().compareTo(combined.getCalculatedScore()));
                assertEquals(single.getRiskLevel(), combined.getRiskLevel());
            }));
        }
        return tests.stream();
    }

    @TestFactory Stream<DynamicTest> numericRequirementsKeepDecisionsAcrossBehaviors() {
        List<DynamicTest> tests = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        cases.forEach(item -> covered.add(item.path("indicatorId").asText()));
        for (String id : registry.indicatorIds()) {
            if (!covered.contains(id)) continue;
            for (Requirement requirement : registry.definition(id).getRequirements()) {
                if (requirement.getValueType() != ValueType.NUMBER || requirement.getCondition().getConditionId() == null
                        || !Arrays.asList(Aggregation.MIN, Aggregation.MAX).contains(requirement.getCrossBehaviorAggregation())
                        || !Arrays.asList(Operator.GE, Operator.LE).contains(requirement.getCondition().getOperator())) continue;
                tests.add(DynamicTest.dynamicTest("数值要求保留多行为结果 [" + requirement.getRequirementId() + "]", () -> {
                    JsonNode compliant = null;
                    for (JsonNode item : cases)
                        if (id.equals(item.path("indicatorId").asText()) && item.path("expectedScore").isNumber()
                                && item.path("expectedScore").asDouble() == 100
                                && item.path("facts").path(requirement.getCondition().getConditionId()).isNumber()) {
                            compliant = item; break;
                        }
                    assertNotNull(compliant, "数值要求需要实际适用且满足的案例");
                    ScoringDecision allCompliant = evaluate(compliant, "first", compliant);
                    assertEquals("DECIDED", allCompliant.getStatus());
                    assertEquals(100, allCompliant.getCalculatedScore().doubleValue(), 0.000001);

                    ObjectNode violation = compliant.deepCopy();
                    BigDecimal limit = requirement.getCondition().getOperand();
                    BigDecimal observed = requirement.getCondition().getOperator() == Operator.GE
                            ? limit.subtract(BigDecimal.ONE) : limit.add(BigDecimal.ONE);
                    ((ObjectNode) violation.get("facts")).put(requirement.getCondition().getConditionId(), observed);
                    violation.putArray("violatedRequirementIds").add(requirement.getRequirementId());
                    violation.put("behavior", compliant.path("behavior").asText()
                            + "；本次核验该要求观测值为" + observed + "，其余已确认事实保持相同。");
                    ScoringDecision single = evaluate(violation, "violation", null);
                    ScoringDecision combined = evaluate(violation, "violation", compliant);
                    assertEquals("DECIDED", single.getStatus());
                    assertEquals("DECIDED", combined.getStatus());
                    assertTrue(single.getEvaluations().stream().anyMatch(a -> requirement.getRequirementId().equals(a.getRequirementId())
                            && a.getJudgment() == ScoringDecision.Judgment.VIOLATED));
                    assertEquals(0, single.getCalculatedScore().compareTo(combined.getCalculatedScore()));
                    assertEquals(single.getRiskLevel(), combined.getRiskLevel());
                }));
            }
        }
        return tests.stream();
    }

    private void assertDecision(JsonNode item, ScoringDecision decision) {
        if (item.path("expectedScore").isNull()) {
            if ("NOT_APPLICABLE".equals(item.path("expectedStatus").asText())) {
                assertEquals("NOT_APPLICABLE", decision.getStatus());
                assertNull(decision.getCalculatedScore());
                assertNull(decision.getRiskLevel());
                assertTrue(decision.getEvaluations().stream().allMatch(a -> a.getJudgment() == ScoringDecision.Judgment.NOT_APPLICABLE));
                return;
            }
            assertEquals("NEEDS_REVIEW", decision.getStatus());
            assertNull(decision.getCalculatedScore());
            assertNull(decision.getRiskLevel());
            Set<String> pending = ids(item, "pendingRequirementIds");
            for (ScoringDecision.Atomic atom : decision.getEvaluations()) {
                if (pending.contains(atom.getRequirementId()))
                    assertEquals(ScoringDecision.Judgment.INSUFFICIENT_EVIDENCE, atom.getJudgment());
                else if (atom.getJudgment() != ScoringDecision.Judgment.NOT_APPLICABLE)
                    assertNotNull(atom.getSatisfaction(), "已完成要求保留独立满足度");
            }
        } else {
            assertEquals("DECIDED", decision.getStatus(), decision.getEvaluations().toString());
            assertEquals(item.get("expectedScore").asDouble(), decision.getCalculatedScore().doubleValue(), 0.000001);
            assertEquals(item.path("expectedRisk").asText(), decision.getRiskLevel().name());
            assertFalse(decision.isConflict(), "案例登记结论应与可计算事实一致");
            Set<String> violated = ids(item, "violatedRequirementIds");
            for (ScoringDecision.Atomic atom : decision.getEvaluations()) {
                if (violated.contains(atom.getRequirementId()))
                    assertTrue(atom.getJudgment() == ScoringDecision.Judgment.VIOLATED
                            || atom.getJudgment() == ScoringDecision.Judgment.UNSATISFIED_OPTIONAL, atom.toString());
            }
        }
    }

    private ScoringDecision evaluate(JsonNode item, String behavior, JsonNode compliant) {
        String id = item.path("indicatorId").asText();
        List<RequirementFinding> findings = findings(item, behavior);
        Map<String, Set<String>> evidence = new HashMap<>();
        evidence.put(behavior, Collections.singleton("e-" + behavior));
        if (compliant != null) {
            for (int index = 0; index < 3; index++) {
                String extra = "compliant-" + index;
                findings.addAll(findings(compliant, extra));
                evidence.put(extra, Collections.singleton("e-" + extra));
            }
        }
        return new RequirementScoringEngine(registry, new UniversalConditionEvaluator()).evaluate(
                new AnalysisScope(1L, 2L, "catalog-business-regression"), id, bundle(id), evidence, findings, Collections.emptyMap());
    }

    private List<RequirementFinding> findings(JsonNode item, String behavior) {
        String id = item.path("indicatorId").asText();
        Set<String> violated = ids(item, "violatedRequirementIds"), pending = ids(item, "pendingRequirementIds");
        Set<String> notApplicable = ids(item, "notApplicableRequirementIds");
        Set<String> valid = new HashSet<>();
        registry.definition(id).getRequirements().forEach(r -> valid.add(r.getRequirementId()));
        assertTrue(valid.containsAll(violated)); assertTrue(valid.containsAll(pending)); assertTrue(valid.containsAll(notApplicable));
        assertTrue(Collections.disjoint(notApplicable, violated)); assertTrue(Collections.disjoint(notApplicable, pending));
        List<RequirementFinding> out = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (Requirement requirement : registry.definition(id).getRequirements()) {
            RequirementFinding finding = new RequirementFinding();
            finding.setIndicatorId(id); finding.setRequirementId(requirement.getRequirementId()); finding.setBehaviorId(behavior);
            finding.setApplicability(notApplicable.contains(requirement.getRequirementId()) ? Applicability.NOT_APPLICABLE : Applicability.APPLICABLE);
            finding.setComplianceStatus(pending.contains(requirement.getRequirementId()) ? ComplianceStatus.INSUFFICIENT_EVIDENCE
                    : violated.contains(requirement.getRequirementId()) && requirement.getDirection() != Direction.OPTIONAL
                    ? ComplianceStatus.NON_COMPLIANT : ComplianceStatus.COMPLIANT);
            finding.setConfidence(BigDecimal.ONE); finding.setReasoning(item.path("behavior").asText());
            finding.setEvidenceIds(Collections.singletonList("e-" + behavior));
            Map<String, ConditionValue> values = new HashMap<>();
            facts(requirement.getCondition(), item.path("facts"), values, consumed, behavior);
            if (requirement.getExceptWhen() != null) facts(requirement.getExceptWhen(), item.path("facts"), values, consumed, behavior);
            finding.setConditionValues(values); out.add(finding);
        }
        item.path("facts").fieldNames().forEachRemaining(key -> assertTrue(consumed.contains(key), "案例引用了未登记事实: " + key));
        return out;
    }

    private void facts(Condition node, JsonNode inputs, Map<String, ConditionValue> values, Set<String> consumed, String behavior) {
        if (node.getAllOf() != null) { node.getAllOf().forEach(c -> facts(c, inputs, values, consumed, behavior)); return; }
        if (node.getAnyOf() != null) { node.getAnyOf().forEach(c -> facts(c, inputs, values, consumed, behavior)); return; }
        if (node.getNotOf() != null) { facts(node.getNotOf(), inputs, values, consumed, behavior); return; }
        JsonNode input = inputs.get(node.getConditionId());
        if (input == null || input.isNull()) return;
        consumed.add(node.getConditionId());
        ConditionValue value = new ConditionValue();
        if (input.isBoolean()) value.setConditionMet(input.booleanValue());
        else { assertTrue(input.isNumber()); value.setObservedValue(input.decimalValue()); value.setObservedUnit(node.getUnit()); }
        value.setEvidenceIds(Collections.singletonList("e-" + behavior)); values.put(node.getConditionId(), value);
    }

    private Set<String> ids(JsonNode item, String field) {
        Set<String> out = new HashSet<>();
        item.path(field).forEach(id -> out.add(id.asText())); return out;
    }

    private ScoringSourceBundle bundle(String id) {
        return dataset.bundle((String) dataset.manifest().get("datasetVersion"), id);
    }
}
