package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.provider.*;
import com.riskwarning.processing.analysis.*;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.riskwarning.processing.analysis.ComplianceTestData.*;

class ComplianceAnalysisServiceTest {
    private final AiChatProvider provider = mock(AiChatProvider.class);
    private final AnalysisContextService contexts = mock(AnalysisContextService.class);
    private final AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
    private final EvidenceChunkRepository evidence = mock(EvidenceChunkRepository.class);
    private final AnalysisAttemptRepository attempts = mock(AnalysisAttemptRepository.class);
    private final AnalysisResultRepository results = mock(AnalysisResultRepository.class);
    private final RetrievalAuditRepository audits = mock(RetrievalAuditRepository.class);
    private final List<AnalysisAttempt> stored = new ArrayList<>();
    private BehaviorAnalysisContextVO context;
    private AnalysisContextVO all;
    private AnalysisRun run;
    private RetrievalAudit audit;
    private ComplianceAnalysisService service;

    @BeforeEach void setup() {
        context = context();
        all = AnalysisContextVO.builder().projectId(1L).assessmentId(2L).analysisRunId("run").runStatus("RUNNING")
                .items(Collections.singletonList(context)).build();
        run = AnalysisRun.start(SCOPE, LocalDateTime.now());
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L)).thenReturn(Optional.of(run));
        when(runs.lockScope("run", 2L, 1L)).thenReturn(Optional.of(run));
        when(contexts.context(2L, 1L, "run")).thenReturn(all);
        when(evidence.findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(context.getEvidenceIds()))
                .thenReturn(Collections.singletonList(EVIDENCE));
        when(provider.modelId()).thenReturn("test-model");
        when(attempts.findFirstByAnalysisRunIdAndBehaviorIdOrderByAttemptNoDesc("run", "b"))
                .thenAnswer(i -> stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size()-1)));
        when(attempts.saveAndFlush(any())).thenAnswer(i -> { AnalysisAttempt a = i.getArgument(0); stored.add(a); return a; });
        audit = RetrievalAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").behaviorId("b")
                .analysisStatus(AnalysisAuditStatus.WAITING_ANALYSIS).build();
        when(audits.findByAnalysisRunIdAndBehaviorId("run", "b")).thenReturn(Optional.of(audit));
        service = new ComplianceAnalysisService(Optional.of(provider), contexts, runs, evidence,
                new CompliancePromptRenderer(), new ComplianceResponseValidator(), new ComplianceAuditWriter(attempts, results, audits, runs));
    }
    private ComplianceAnalysisOutcome analyze() { return service.analyze(SCOPE, "b", rules(), Collections.singletonMap("industry", "测试行业")); }

    @Test void normalResultBindsServerMetadataAndPersistsOneAudit() {
        when(provider.chat(anyString())).thenReturn(response().toString());
        ComplianceAnalysisOutcome outcome = analyze();
        assertEquals(AnalysisAuditStatus.ANALYZED, outcome.getStatus());
        assertEquals("run", outcome.getResult().getAnalysisRunId()); assertEquals(2L, outcome.getResult().getAssessmentId());
        assertEquals("b", outcome.getResult().getBehaviorId()); assertEquals(Boolean.FALSE, outcome.getResult().getConditionMet());
        assertEquals("test-model", outcome.getResult().getModelVersion());
        assertEquals(CompliancePromptRenderer.VERSION, outcome.getResult().getPromptVersion());
        assertEquals(1, stored.size()); assertEquals(AnalysisAttemptStatus.VALID, stored.get(0).getStatus());
        assertEquals(64, stored.get(0).getContextHash().length());
        verify(results).saveAndFlush(outcome.getResult()); assertEquals(AnalysisAuditStatus.ANALYZED, audit.getAnalysisStatus());
    }
    @Test void repairsOnlyOnceWithOriginalResponseAndExactErrors() {
        when(provider.chat(anyString())).thenReturn("invalid-json", response().toString());
        assertNotNull(analyze().getResult());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class); verify(provider, times(2)).chat(prompt.capture());
        assertTrue(prompt.getAllValues().get(1).contains("invalid-json"));
        assertTrue(prompt.getAllValues().get(1).contains("validationErrors"));
        assertEquals(AnalysisAttemptStatus.INVALID, stored.get(0).getStatus());
        assertEquals(AnalysisAttemptStatus.VALID, stored.get(1).getStatus());
        assertEquals(1, stored.get(0).getAttemptNo()); assertEquals(2, stored.get(1).getAttemptNo());
        assertEquals(stored.get(0).getContextHash(), stored.get(1).getContextHash());
    }
    @Test void twoInvalidResponsesRemainReviewWithoutResult() {
        when(provider.chat(anyString())).thenReturn("bad", "bad-again");
        assertEquals(AnalysisAuditStatus.NEEDS_REVIEW, analyze().getStatus());
        assertEquals(2, stored.size()); verify(provider, times(2)).chat(anyString());
        verifyNoInteractions(results); assertEquals(AnalysisAuditStatus.NEEDS_REVIEW, audit.getAnalysisStatus());
        assertEquals("MODEL_REPAIR_EXHAUSTED", audit.getErrorCode());
    }
    @Test void noApplicableIndicatorDoesNotSaveResult() {
        ObjectNode n = response(); n.putNull("indicatorId"); n.remove("conditionMet");
        n.put("applicable", "NOT_APPLICABLE"); n.put("complianceStatus", "NO_APPLICABLE_CANDIDATE");
        when(provider.chat(anyString())).thenReturn(n.toString());
        assertEquals(AnalysisAuditStatus.NO_APPLICABLE_CANDIDATE, analyze().getStatus());
        verifyNoInteractions(results); assertEquals(AnalysisAttemptStatus.VALID, stored.get(0).getStatus());
    }
    @Test void validMissingEvidenceOrConflictPreservesUndecidedStatusWithoutRepair() {
        ObjectNode n = response(); n.remove("conditionMet"); n.put("applicable", "UNKNOWN");
        n.put("complianceStatus", "INSUFFICIENT_EVIDENCE");
        when(provider.chat(anyString())).thenReturn(n.toString());
        ComplianceAnalysisOutcome outcome = analyze();
        assertEquals(AnalysisAuditStatus.INSUFFICIENT_EVIDENCE, outcome.getStatus());
        assertNull(outcome.getResult().getConditionMet()); assertEquals(AnalysisAttemptStatus.VALID, stored.get(0).getStatus());
        verify(provider, times(1)).chat(anyString());
    }
    @Test void providerExhaustionIsAuditedAndRethrownWithoutBusinessRetry() {
        LlmProviderException failure = new LlmProviderException("timeout", -1, true);
        when(provider.chat(anyString())).thenThrow(failure);
        assertSame(failure, assertThrows(LlmProviderException.class, this::analyze));
        assertEquals(AnalysisAttemptStatus.PROVIDER_FAILED, stored.get(0).getStatus());
        assertNull(stored.get(0).getRawResponse()); verify(provider, times(1)).chat(anyString()); verifyNoInteractions(results);
    }
    @Test void providerFailureDuringRepairRetainsBothAttempts() {
        when(provider.chat(anyString())).thenReturn("bad").thenThrow(new LlmProviderException("limit", 429, true));
        assertThrows(LlmProviderException.class, this::analyze);
        assertEquals(2, stored.size()); assertEquals(AnalysisAttemptStatus.PROVIDER_FAILED, stored.get(1).getStatus());
        verifyNoInteractions(results);
    }
    @Test void blockedFactDoesNotCallModel() {
        context.setReadyForAnalysis(false); context.setBlockedReason("INSUFFICIENT_EVIDENCE");
        assertThrows(IllegalStateException.class, this::analyze); verifyNoInteractions(provider, attempts, results);
    }
    @Test void historicalRunIsNotResumed() {
        run.completeWithoutDecision(LocalDateTime.now().plusSeconds(1));
        assertThrows(IllegalStateException.class, this::analyze); verifyNoInteractions(provider, contexts, attempts, results);
    }
    @Test void mismatchedContextAndCandidatesFailBeforeModel() {
        all.setAnalysisRunId("other"); assertThrows(IllegalStateException.class, this::analyze);
        all.setAnalysisRunId("run"); context.getIndicatorCandidates().get(0).setBehaviorId("foreign");
        assertThrows(IllegalArgumentException.class, this::analyze); verifyNoInteractions(provider, attempts, results);
    }
    @Test void missingEvidenceIsNotReplacedByDescriptions() {
        when(evidence.findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(anyCollection())).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class, this::analyze); verifyNoInteractions(provider, attempts, results);
    }
    @Test void staleRetrievalVersionOrMissingSnapshotFailsBeforeModel() {
        context.getIndicatorCandidates().get(0).setEmbeddingVersion("other-version");
        assertThrows(IllegalArgumentException.class, this::analyze);
        context.getIndicatorCandidates().get(0).setEmbeddingVersion("v1");
        context.getIndicatorCandidates().forEach(c -> c.setSnapshotAvailable(false));
        context.getRegulationCandidates().forEach(c -> c.setSnapshotAvailable(false));
        assertThrows(IllegalArgumentException.class, this::analyze); verifyNoInteractions(provider, attempts, results);
    }
    @Test void invalidRegisteredInputRequirementFailsBeforeModel() {
        Map<String, RuleInputRequirement> input = rules(); input.get("range").setInputUnit(null);
        assertThrows(IllegalArgumentException.class, () -> service.analyze(SCOPE, "b", input, Collections.emptyMap()));
        verifyNoInteractions(provider, attempts, results);
    }
    @Test void promptIncludesFullSnapshotAndOnlyTargetBehavior() {
        List<RetrievalCandidateVO> candidates = new ArrayList<>(context.getIndicatorCandidates());
        for (int i=0; i<60; i++) candidates.add(candidate("extra-"+i, "INDICATOR"));
        context.setIndicatorCandidates(candidates);
        all.setItems(Arrays.asList(context, BehaviorAnalysisContextVO.builder().behaviorId("other").description("OTHER_FACT_SECRET").build()));
        context.setCalculationDetails("EXPECTED_SCORE_SECRET");
        when(provider.chat(anyString())).thenReturn(response().toString()); analyze();
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class); verify(provider).chat(prompt.capture());
        assertTrue(prompt.getValue().contains("extra-59")); assertTrue(prompt.getValue().contains(EVIDENCE.getText()));
        assertFalse(prompt.getValue().contains("OTHER_FACT_SECRET")); assertFalse(prompt.getValue().contains("EXPECTED_SCORE_SECRET"));
    }
}
