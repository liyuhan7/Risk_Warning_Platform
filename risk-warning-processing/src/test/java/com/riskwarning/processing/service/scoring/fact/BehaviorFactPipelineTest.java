package com.riskwarning.processing.service.scoring.fact;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.provider.AiChatProvider;
import com.riskwarning.processing.dto.analysis.BehaviorAnalysisContextVO;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.rule.v2.*;
import com.riskwarning.processing.service.ComplianceAuditWriter;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 按行为抽取使用真实审核目录和规则；模型替身只用于验证调用、映射和评分契约。 */
class BehaviorFactPipelineTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String WAGE1 = "05621446-715b-4e95-bdb2-72da0963472b";
    static final String WAGE2 = "256cf5be-9e8e-4c76-9f40-951a9281781d";
    static final String OVERTIME = "4ad95dd2-bcfb-4c74-bbe0-2832045c9f84";
    static final String NONCOMPETE = "482dec23-9dd6-4273-a431-9c0c5fa75a95";
    static final String OT_REQ = "req-22ea9d48093c36751d80b146";

    @Test void catalogCoversEveryConditionAndGateAndSharesSemanticDailyObservation() throws Exception {
        Fixture f = new Fixture(OVERTIME); f.catalog.validate(f.registry);
        assertEquals(132, f.catalog.bindings().size());
        assertEquals(76, f.catalog.bindings().stream().filter(b -> b.getConditionId() != null).count());
        assertEquals(f.key(OT_REQ + "-daily-hours"), f.key(OT_REQ + "-ordinary-daily-hours"));
        assertNotEquals(f.key(OT_REQ + "-monthly-hours"), f.key(OT_REQ + "-daily-hours"));
        assertNotEquals(f.key("req-77a78e04789e1715632d433c-notice-days"), f.key("req-91665e5a51cf31c60b2afb14-notice-days"));
        f.registry = ScoringRuleRegistry.fromJson("{\"schemaVersion\":\"2.0\",\"registryVersion\":\"other\",\"purpose\":\"TEST\",\"rules\":{}}", true);
        assertThrows(IllegalArgumentException.class, () -> f.catalog.validate(f.registry));
    }

    @Test void twoIndicatorsUseOneCallAndProgramProducesTheirIndependentFindings() throws Exception {
        Fixture f = new Fixture(WAGE1, WAGE2); f.prepare(); List<AnalysisResult> results = f.analyze();
        assertEquals(1, f.prompts.size()); assertEquals(2, results.size());
        assertEquals(3, f.plan().getQuestions().size());
        assertFalse(f.prompts.get(0).contains("\"weight\"")); assertFalse(f.prompts.get(0).contains("\"riskIntervals\""));
        assertFalse(f.prompts.get(0).contains(f.chunk.getId())); assertFalse(f.prompts.get(0).contains(WAGE1));
        for (AnalysisResult result : results) {
            assertEquals(ComplianceStatus.COMPLIANT, result.getComplianceStatus());
            assertEquals("FACTS_DERIVED", result.getRequirementFindings().get(0).getJudgmentSource());
            assertEquals(Collections.singletonList(f.chunk.getId()), result.getRequirementFindings().get(0).getEvidenceIds());
            assertEquals(100, f.score(result).getCalculatedScore().intValue()); assertFalse(f.score(result).isConflict());
        }
    }

    @Test void thirtySevenHoursAndTwentyFiveMonthsUseExistingWeightsAndRiskBands() throws Exception {
        Fixture overtime = new Fixture(OVERTIME); overtime.values.put(overtime.key(OT_REQ + "-monthly-hours"), 37);
        overtime.values.put(overtime.key(OT_REQ + "-daily-hours"), 3); overtime.prepare();
        AnalysisResult result = overtime.analyze().get(0); ScoringDecision decision = overtime.score(result);
        assertEquals(40, decision.getCalculatedScore().intValue()); assertEquals(RiskLevelEnum.HIGH_RISK, decision.getRiskLevel()); assertFalse(decision.isConflict());
        RequirementFinding finding = result.getRequirementFindings().stream().filter(r -> OT_REQ.equals(r.getRequirementId())).findFirst().get();
        assertEquals(0, finding.getConditionValues().get(OT_REQ + "-daily-hours").getObservedValue().compareTo(finding.getConditionValues().get(OT_REQ + "-ordinary-daily-hours").getObservedValue()));
        Fixture noncompete = new Fixture(NONCOMPETE); noncompete.values.put(noncompete.key("cond-a9f8acf51c525ef9d37a3554"), 25); noncompete.prepare();
        ScoringDecision exceeded = noncompete.score(noncompete.analyze().get(0));
        assertEquals(11.7647058823529, exceeded.getCalculatedScore().doubleValue(), 0.000001); assertEquals(RiskLevelEnum.HIGH_RISK, exceeded.getRiskLevel());
    }

    @Test void missingOnlyOneAnswerRepairsOnlyThatQuestionAndRetainsKnownValues() throws Exception {
        Fixture f = new Fixture(WAGE1, WAGE2); f.prepare();
        f.transform = response -> { if (f.prompts.size() == 1) ((ArrayNode)response.get("answers")).remove(0); return response; };
        f.analyze(); assertEquals(2, f.prompts.size());
        assertEquals(1, f.input(f.prompts.get(1)).path("questions").size());
        assertEquals(3, f.input(f.prompts.get(0)).path("questions").size());
        verify(f.audit, times(2)).attempt(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), anyList(), nullable(String.class), nullable(String.class));
    }

    @Test void statedLackOfEvidenceIsNotReaskedOrScoredAsFalse() throws Exception {
        Fixture f = new Fixture(WAGE1); f.prepare();
        f.transform = response -> {
            for (JsonNode row : response.path("answers")) if (row.has("booleanValue")) {
                ObjectNode value = (ObjectNode)row; value.put("status", "NOT_STATED"); value.remove("booleanValue"); value.set("evidenceIds", M.createArrayNode());
            }
            return response;
        };
        AnalysisResult result = f.analyze().get(0); assertEquals(1, f.prompts.size());
        assertEquals(ComplianceStatus.NEEDS_REVIEW, result.getComplianceStatus()); assertNull(f.score(result).getCalculatedScore());
        assertEquals(ScoringDecision.Judgment.INSUFFICIENT_EVIDENCE, f.score(result).getEvaluations().get(0).getJudgment());
    }

    @Test void secondRepairFailureKeepsCompletedRequirementScores() throws Exception {
        Fixture f = new Fixture(OVERTIME); f.prepare(); String monthly = f.questionId(f.key(OT_REQ + "-monthly-hours"));
        f.transform = response -> { ArrayNode rows = M.createArrayNode(); for (JsonNode row : response.path("answers")) if (!monthly.equals(row.path("questionId").asText())) rows.add(row); response.set("answers", rows); return response; };
        ScoringDecision decision = f.score(f.analyze().get(0));
        assertEquals(2, f.prompts.size()); assertNull(decision.getCalculatedScore());
        assertTrue(decision.getEvaluations().stream().anyMatch(item -> item.getEarned() != null && item.getEarned().intValue() == 40));
    }

    @Test void malformedJsonAndUnknownEvidenceAreRepairedAtMostOnce() throws Exception {
        Fixture f = new Fixture(WAGE1); f.prepare();
        f.transform = response -> { if (f.prompts.size() == 1) for (JsonNode row : response.path("answers")) ((ObjectNode)row).set("evidenceIds", M.createArrayNode().add("E999")); return response; };
        assertEquals(100, f.score(f.analyze().get(0)).getCalculatedScore().intValue()); assertEquals(2, f.prompts.size());
        Fixture broken = new Fixture(WAGE1); broken.prepare(); broken.rawOverride = "{unfinished";
        assertNull(broken.score(broken.analyze().get(0)).getCalculatedScore()); assertEquals(2, broken.prompts.size());
    }

    @Test void validPartialAndRepairAuditAreReusedWithoutNetworkOnReplay() throws Exception {
        Fixture f = new Fixture(WAGE1, WAGE2); f.prepare();
        f.transform = response -> { if (f.prompts.size() == 1) ((ArrayNode)response.get("answers")).remove(0); return response; };
        List<AnalysisResult> first = f.analyze(); assertEquals(2, f.prompts.size());
        List<AnalysisResult> replay = f.analyze(); assertEquals(2, f.prompts.size());
        assertEquals(first.get(0).getRequirementFindings(), replay.get(0).getRequirementFindings());
        f.history.forEach(item -> item.setModelVersion("different")); f.analyze(); assertEquals(3, f.prompts.size());
    }

    @Test void questionAndInputBudgetsAreFrozenAndOversizeNeverTruncatesEvidence() throws Exception {
        Fixture f = new Fixture(OVERTIME); f.properties.setFactMaxQuestions(3); f.prepare();
        for (Batch batch : f.plan().getBatches()) { assertTrue(batch.getQuestionIds().size() <= 3); assertTrue(BehaviorFactPlanner.codePoints(batch.getPrompt()) <= 12000); }
        int count = f.plan().getBatches().size(); f.properties.setFactMaxQuestions(100);
        f.analyze(); assertEquals(count, f.prompts.size());
        Fixture oversized = new Fixture(WAGE1); oversized.properties.setFactMaxInputCodePoints(100); oversized.prepare();
        oversized.analyze(); assertTrue(oversized.prompts.isEmpty()); assertEquals(2, oversized.plan().getOversizedQuestionIds().size());
    }

    @Test void knownZeroFalseAndUnknownAreDifferentAndTypesCannotBeCoerced() {
        Question question = new Question(); question.setId("Q1"); Definition definition = new Definition(); definition.setType("BOOLEAN"); question.setDefinition(definition);
        BehaviorFactResponseValidator validator = new BehaviorFactResponseValidator();
        String prefix = "{\"answers\":[{\"questionId\":\"Q1\",\"status\":\"KNOWN\",\"evidenceIds\":[\"E1\"],\"booleanValue\":";
        assertFalse(validator.validate(prefix + "false}]}", Collections.singleton(question), Collections.singleton("E1")).get("Q1").getBooleanValue());
        assertTrue(validator.validate(prefix + "\"false\"}]}", Collections.singleton(question), Collections.singleton("E1")).isEmpty());
        assertTrue(validator.validate(prefix + "0}]}", Collections.singleton(question), Collections.singleton("E1")).isEmpty());
        definition.setType("NUMBER"); definition.setUnit("HOURS");
        assertEquals(BigDecimal.ZERO, validator.validate(prefix.replace("booleanValue", "numberValue") + "0,\"unit\":\"HOURS\"}]}", Collections.singleton(question), Collections.singleton("E1")).get("Q1").getNumberValue());
    }

    @Test void applicabilityAndExceptionsStayIndependentOfSharedValues() throws Exception {
        Fixture f = new Fixture(OVERTIME); f.values.put(f.key(OT_REQ + "-monthly-hours"), 37); f.values.put(f.key(OT_REQ + "-emergency"), true); f.prepare();
        assertEquals(100, f.score(f.analyze().get(0)).getCalculatedScore().intValue());
        Fixture notApplicable = new Fixture(WAGE1); notApplicable.prepare();
        notApplicable.transform = response -> { for (JsonNode row : response.path("answers")) if (row.has("applicability")) ((ObjectNode)row).put("applicability", "NOT_APPLICABLE"); return response; };
        assertEquals("NOT_APPLICABLE", notApplicable.score(notApplicable.analyze().get(0)).getStatus());
    }

    static class Fixture {
        final AnalysisScope scope = new AnalysisScope(1L, 2L, "fact-test");
        final BehaviorFactProperties properties = new BehaviorFactProperties();
        final SharedFactCatalog catalog = new SharedFactCatalog(properties);
        ScoringRuleRegistry registry = new ScoringRuleRegistry("classpath:rules/labor-reviewed-test-v2.json", true);
        final ScoringDataset dataset;
        final ScoringRunSnapshot snapshot = new ScoringRunSnapshot();
        final EvidenceChunk chunk = EvidenceChunk.create(3L, 1L, 2L, "材料.txt", 1, 0, null, null, "秦某采用标准工时，正常每日8小时每周40小时，月加班37小时；公司已协商并采取措施。", LocalDateTime.now());
        final BehaviorAnalysisContextVO behavior = BehaviorAnalysisContextVO.builder().behaviorId("b").subject("公司").action("安排工作").description(chunk.getText()).sourceDocumentId(3L)
                .evidenceIds(Collections.singletonList(chunk.getId())).retrievalStatus("SUCCESS").evidenceComplete(true).snapshotAvailable(true).build();
        final EvidenceChunkRepository evidence = mock(EvidenceChunkRepository.class);
        final AiChatProvider chat = mock(AiChatProvider.class);
        final ComplianceAuditWriter audit = mock(ComplianceAuditWriter.class);
        final AnalysisAttemptRepository attempts = mock(AnalysisAttemptRepository.class);
        final List<AnalysisAttempt> history = new CopyOnWriteArrayList<>();
        final List<String> prompts = new ArrayList<>();
        final Map<String, Object> values = new HashMap<>();
        Function<ObjectNode, ObjectNode> transform = Function.identity();
        String rawOverride;

        Fixture(String... indicators) throws Exception {
            properties.setFactMode(BehaviorFactProperties.Mode.BEHAVIOR);
            dataset = new ScoringDataset((Files.exists(Paths.get("documents/data")) ? Paths.get("documents/data") : Paths.get("../documents/data")).toString());
            snapshot.setAnalysisRunId(scope.getAnalysisRunId()); snapshot.setProjectId(1L); snapshot.setAssessmentId(2L);
            snapshot.setRuleDefinitions(new TreeMap<>()); snapshot.setSourceBundles(new TreeMap<>()); snapshot.setIndicatorBehaviors(new TreeMap<>());
            String version = (String)dataset.manifest().get("datasetVersion");
            for (String indicator : indicators) {
                snapshot.getRuleDefinitions().put(indicator, registry.definition(indicator)); snapshot.getSourceBundles().put(indicator, dataset.bundle(version, indicator));
                snapshot.getIndicatorBehaviors().put(indicator, Collections.singletonList("b"));
            }
            when(evidence.findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(anyList())).thenReturn(Collections.singletonList(chunk));
            when(chat.modelId()).thenReturn("controlled-facts");
            when(chat.chat(anyString())).thenAnswer(call -> { String prompt = call.getArgument(0); prompts.add(prompt); return rawOverride == null ? transform.apply(response(prompt)).toString() : rawOverride; });
            when(attempts.findByAnalysisRunIdAndBehaviorIdOrderByAttemptNoAsc(anyString(), anyString())).thenAnswer(call -> new ArrayList<>(history));
            doAnswer(call -> {
                history.add(AnalysisAttempt.builder().projectId(1L).assessmentId(2L).analysisRunId(scope.getAnalysisRunId()).behaviorId("b")
                        .contextHash(call.getArgument(2)).modelVersion(call.getArgument(3)).promptVersion(call.getArgument(4)).rawResponse(call.getArgument(5)).status(call.getArgument(6)).build()); return null;
            }).when(audit).attempt(any(), anyString(), anyString(), anyString(), anyString(), nullable(String.class), any(), anyList(), nullable(String.class), nullable(String.class));
        }

        void prepare() { snapshot.setFactExtractionPlan(new BehaviorFactPlanner(catalog, registry, properties, evidence, Optional.of(chat)).prepare(snapshot, Collections.singletonMap("b", behavior))); }
        BehaviorPlan plan() { return snapshot.getFactExtractionPlan().getBehaviors().get("b"); }
        String key(String condition) { return catalog.bindings().stream().filter(binding -> condition.equals(binding.getConditionId())).findFirst().get().getFactKey(); }
        String questionId(String key) { return plan().getQuestions().stream().filter(question -> key.equals(question.getFactKey())).findFirst().get().getId(); }
        JsonNode input(String prompt) throws Exception { return M.readTree(prompt.substring(prompt.indexOf("{\"subject\""))); }
        ObjectNode response(String prompt) throws Exception {
            ObjectNode result = M.createObjectNode(); ArrayNode rows = result.putArray("answers");
            for (JsonNode question : input(prompt).path("questions")) {
                String id = question.path("id").asText(); Question definition = plan().getQuestions().stream().filter(q -> id.equals(q.getId())).findFirst().get();
                ObjectNode row = rows.addObject().put("questionId", id).put("status", "KNOWN").put("confidence", 0.95); row.putArray("evidenceIds").add("E1");
                Object override = values.get(definition.getFactKey());
                if ("APPLICABILITY".equals(definition.getDefinition().getType())) row.put("applicability", "APPLICABLE");
                else if ("BOOLEAN".equals(definition.getDefinition().getType())) {
                    boolean exception = catalog.bindings().stream().anyMatch(binding -> definition.getFactKey().equals(binding.getFactKey()) && binding.getConditionId() != null && binding.getConditionId().endsWith("-emergency"));
                    row.put("booleanValue", override == null ? !exception : (Boolean)override);
                } else {
                    BigDecimal number = override == null ? defaultNumber(definition.getFactKey()) : new BigDecimal(override.toString());
                    row.put("numberValue", number).put("unit", definition.getDefinition().getUnit());
                }
            }
            return result;
        }
        BigDecimal defaultNumber(String key) {
            for (Binding binding : plan().getBindings()) if (key.equals(binding.getFactKey())) {
                for (Requirement req : snapshot.getRuleDefinitions().get(binding.getIndicatorId()).getRequirements()) if (req.getRequirementId().equals(binding.getRequirementId()))
                    for (Condition leaf : SharedFactCatalog.leaves(req.getCondition())) if (leaf.getConditionId().equals(binding.getConditionId())) return leaf.getOperand();
            }
            return BigDecimal.ONE;
        }
        List<AnalysisResult> analyze() {
            Map<String, Answer> facts = new LlmBehaviorFactExtractor(Optional.of(chat), audit, attempts).extract(scope, snapshot.getFactExtractionPlan(), plan());
            return new BehaviorFactMapper(new RequirementScoringEngine(registry, new UniversalConditionEvaluator())).map(scope, snapshot, behavior, facts);
        }
        ScoringDecision score(AnalysisResult result) {
            return new RequirementScoringEngine(registry, new UniversalConditionEvaluator()).evaluate(scope, result.getIndicatorId(), snapshot.getSourceBundles().get(result.getIndicatorId()),
                    Collections.singletonMap("b", Collections.singleton(chunk.getId())), result.getRequirementFindings(), Collections.emptyMap());
        }
    }
}
