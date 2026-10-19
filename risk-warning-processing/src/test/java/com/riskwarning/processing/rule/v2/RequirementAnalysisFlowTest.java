package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.provider.AiChatProvider;
import com.riskwarning.processing.client.ScoringDatasetClient;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.service.*;
import com.riskwarning.processing.service.scoring.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.*;
import static com.riskwarning.processing.rule.v2.ScoringTestData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 业务流程验证：候选只负责命中指标，评分法规由登记关系补齐，模型事实交由引擎计分。 */
class RequirementAnalysisFlowTest {
    @Test void missingLawCandidatesAreSupplementedAndTwoRequirementsScoreIndependently() throws Exception {
        AnalysisScope scope = new AnalysisScope(1L, 2L, "run");
        EvidenceChunk chunk = EvidenceChunk.create(3L, 1L, 2L, "业务材料.txt", 1, 0, null, null,
                "措施一已经履行，措施二尚未履行。", LocalDateTime.now());
        ScoringSourceBundle source = bundle("r1", "r2");
        ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(registry(source, requirement("r1", "REQUIRED"), requirement("r2", "REQUIRED")).toString(), true);
        ObjectNode response = M.createObjectNode().put("schemaVersion", "1.2").put("indicatorId", "i");
        ArrayNode findings = response.putArray("requirementFindings");
        for (String id : Arrays.asList("r1", "r2")) {
            boolean satisfied = "r1".equals(id);
            ObjectNode finding = findings.addObject().put("requirementId", id).put("applicability", "APPLICABLE")
                    .put("complianceStatus", satisfied ? "COMPLIANT" : "NON_COMPLIANT").put("confidence", 0.95)
                    .put("reasoning", satisfied ? "原文说明措施一已履行" : "原文说明措施二未履行");
            finding.putArray("evidenceIds").add(chunk.getId());
            finding.putObject("conditionValues").putObject("c-" + id).put("conditionMet", satisfied).putArray("evidenceIds").add(chunk.getId());
        }
        AiChatProvider chat = mock(AiChatProvider.class); when(chat.modelId()).thenReturn("controlled-model"); when(chat.chat(anyString())).thenReturn(response.toString());
        EvidenceChunkRepository evidence = mock(EvidenceChunkRepository.class);
        when(evidence.findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(anyList())).thenReturn(Collections.singletonList(chunk));
        RequirementAnalysisService semantic = new RequirementAnalysisService(Optional.of(chat), evidence, mock(ComplianceAuditWriter.class));
        BehaviorAnalysisContextVO item = BehaviorAnalysisContextVO.builder().behaviorId("b").description(chunk.getText())
                .sourceDocumentId(3L).evidenceIds(Collections.singletonList(chunk.getId())).retrievalStatus("SUCCESS")
                .evidenceComplete(true).snapshotAvailable(true).analysisStatus("RECALL_GAP").readyForAnalysis(false)
                .indicatorCandidates(Collections.singletonList(RetrievalCandidateVO.builder().candidateId("i").snapshotAvailable(true).build()))
                .regulationCandidates(Collections.emptyList()).build();
        AnalysisContextService contexts = mock(AnalysisContextService.class);
        when(contexts.context(2L, 1L, "run")).thenReturn(AnalysisContextVO.builder().items(Collections.singletonList(item)).build());
        ScoringDatasetClient datasets = mock(ScoringDatasetClient.class);
        when(datasets.current()).thenReturn(Collections.singletonMap("datasetVersion", source.getDatasetVersion()));
        when(datasets.bundle(source.getDatasetVersion(), "i")).thenReturn(source);
        ScoringPersistenceService writer = mock(ScoringPersistenceService.class);
        when(writer.freeze(eq(scope), any())).thenAnswer(invocation -> invocation.getArgument(1));
        AnalysisResultRepository results = mock(AnalysisResultRepository.class); List<AnalysisResult> saved = new ArrayList<>();
        doAnswer(invocation -> { saved.add(invocation.getArgument(1)); return null; }).when(writer).analysis(eq(scope), any());
        when(results.findByAssessmentIdAndAnalysisRunId(2L, "run")).thenAnswer(invocation -> saved);
        RetrievalAuditRepository retrievals = mock(RetrievalAuditRepository.class);
        when(retrievals.findByAssessmentIdAndAnalysisRunId(2L, "run")).thenReturn(Collections.singletonList(RetrievalAudit.builder()
                .behaviorId("b").queryText(chunk.getText()).candidates(Collections.emptyList()).build()));
        ScoringReportPreparationService reports = mock(ScoringReportPreparationService.class);
        RequirementEvaluationProjection projection = mock(RequirementEvaluationProjection.class);
        ScoringOrchestrator orchestrator = new ScoringOrchestrator(datasets, registry, new RequirementScoringEngine(registry, new UniversalConditionEvaluator()),
                semantic, writer, contexts, mock(ScoringRunSnapshotRepository.class), results, retrievals, reports, projection);
        Behavior behavior = Behavior.builder().id("b").evidenceIds(Collections.singletonList(chunk.getId())).build();
        orchestrator.process(null, scope, Collections.singletonList(behavior));
        assertEquals(1, saved.size()); assertEquals("1.2", saved.get(0).getSchemaVersion());
        assertEquals(2, saved.get(0).getRegulationIds().size()); assertEquals(2, saved.get(0).getRequirementFindings().size());
        ArgumentCaptor<ScoringDecision> decision = ArgumentCaptor.forClass(ScoringDecision.class);
        verify(writer).decision(eq(scope), eq(source), any(), decision.capture(), anyList());
        assertEquals(50.0, decision.getValue().getCalculatedScore().doubleValue());
        assertEquals(ScoringDecision.Judgment.SATISFIED, decision.getValue().getEvaluations().get(0).getJudgment());
        assertEquals(ScoringDecision.Judgment.VIOLATED, decision.getValue().getEvaluations().get(1).getJudgment());
        assertEquals(GapType.MISSING_ACTION, decision.getValue().getEvaluations().get(1).getGapType());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class); verify(chat).chat(prompt.capture());
        String contextJson = prompt.getValue().substring(prompt.getValue().indexOf("\n") + 1);
        com.fasterxml.jackson.databind.JsonNode context = M.readTree(contextJson);
        assertEquals(2, context.path("source").path("relations").size());
        assertFalse(context.path("requirements").get(0).has("weight"));
        assertFalse(context.path("requirements").get(0).has("grading"));
        verify(projection).project(scope); verify(reports).prepare(null, scope, false);
    }
}
