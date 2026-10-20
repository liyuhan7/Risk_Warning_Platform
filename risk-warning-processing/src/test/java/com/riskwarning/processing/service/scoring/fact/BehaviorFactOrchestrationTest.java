package com.riskwarning.processing.service.scoring.fact;

import com.riskwarning.common.dto.scoring.ScoringJson;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ScoringDatasetClient;
import com.riskwarning.processing.dto.analysis.AnalysisContextVO;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.rule.v2.*;
import com.riskwarning.processing.service.AnalysisContextService;
import com.riskwarning.processing.service.scoring.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.riskwarning.processing.service.scoring.fact.BehaviorFactPipelineTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BehaviorFactOrchestrationTest {
    @Test void frozenBehaviorModeIgnoresLaterSwitchAndReplayDoesNotCallModelAgain() throws Exception {
        Fixture f = new Fixture(WAGE1, WAGE2); f.prepare(); f.snapshot.setRegistryVersion(f.registry.version());
        RequirementScoringEngine engine = new RequirementScoringEngine(f.registry, new UniversalConditionEvaluator());
        RequirementAnalysisService legacy = mock(RequirementAnalysisService.class);
        ScoringPersistenceService writer = mock(ScoringPersistenceService.class);
        AnalysisResultRepository results = mock(AnalysisResultRepository.class); Map<String, AnalysisResult> saved = new ConcurrentHashMap<>();
        when(results.findByAnalysisRunIdAndBehaviorIdAndIndicatorId(anyString(), anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(2))));
        when(results.findByAssessmentIdAndAnalysisRunId(2L, f.scope.getAnalysisRunId())).thenAnswer(call -> new ArrayList<>(saved.values()));
        doAnswer(call -> { AnalysisResult result = call.getArgument(1); saved.putIfAbsent(result.getIndicatorId(), result); return null; }).when(writer).analysis(eq(f.scope), any());
        AnalysisContextService contexts = mock(AnalysisContextService.class);
        when(contexts.context(2L, 1L, f.scope.getAnalysisRunId())).thenReturn(AnalysisContextVO.builder().items(Collections.singletonList(f.behavior)).build());
        ScoringRunSnapshotRepository snapshots = mock(ScoringRunSnapshotRepository.class); when(snapshots.findById(f.scope.getAnalysisRunId())).thenReturn(Optional.of(f.snapshot));
        RetrievalAuditRepository retrievals = mock(RetrievalAuditRepository.class);
        when(retrievals.findByAssessmentIdAndAnalysisRunId(2L, f.scope.getAnalysisRunId())).thenReturn(Collections.singletonList(RetrievalAudit.builder().behaviorId("b").build()));
        ScoringReportPreparationService reports = mock(ScoringReportPreparationService.class); RequirementEvaluationProjection projection = mock(RequirementEvaluationProjection.class);
        ScoringOrchestrator orchestrator = new ScoringOrchestrator(mock(ScoringDatasetClient.class), f.registry, engine, legacy, writer, contexts, snapshots, results, retrievals, reports, projection);
        BehaviorFactExecutor executor = new BehaviorFactExecutor(f.properties);
        try {
            BehaviorFactAnalysisService analysis = new BehaviorFactAnalysisService(f.properties,
                    new BehaviorFactPlanner(f.catalog, f.registry, f.properties, f.evidence, Optional.of(f.chat)),
                    new LlmBehaviorFactExtractor(Optional.of(f.chat), f.audit, f.attempts), new BehaviorFactMapper(engine), executor, writer, results);
            ReflectionTestUtils.setField(orchestrator, "factAnalysis", analysis);
            f.properties.setFactMode(BehaviorFactProperties.Mode.INDICATOR);
            Behavior behavior = Behavior.builder().id("b").evidenceIds(f.behavior.getEvidenceIds()).build();
            orchestrator.process(null, f.scope, Collections.singletonList(behavior));
            assertEquals(1, f.prompts.size()); assertEquals(2, saved.size()); verifyNoInteractions(legacy);
            Map<String, String> hashes = new HashMap<>(); saved.forEach((id, result) -> hashes.put(id, ScoringJson.hash(result)));
            orchestrator.process(null, f.scope, Collections.singletonList(behavior));
            assertEquals(1, f.prompts.size()); saved.forEach((id, result) -> assertEquals(hashes.get(id), ScoringJson.hash(result)));
            verify(writer, times(4)).decision(eq(f.scope), any(), any(), any(), anyList());
            verify(reports, times(2)).prepare(null, f.scope, false);
        } finally { executor.destroy(); }
    }

    @Test void frozenSnapshotRoundTripsWithPromptsReferencesAndBindings() throws Exception {
        Fixture f = new Fixture(OVERTIME); f.prepare(); String hash = ScoringJson.hash(f.snapshot);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        ScoringRunSnapshot copy = mapper.readValue(mapper.writeValueAsBytes(f.snapshot), ScoringRunSnapshot.class);
        assertEquals(hash, ScoringJson.hash(copy));
        assertEquals(f.plan().getBatches().get(0).getPrompt(), copy.getFactExtractionPlan().getBehaviors().get("b").getBatches().get(0).getPrompt());
        assertEquals(f.plan().getEvidenceReferences(), copy.getFactExtractionPlan().getBehaviors().get("b").getEvidenceReferences());
        assertEquals(f.plan().getBindings(), copy.getFactExtractionPlan().getBehaviors().get("b").getBindings());
    }
}
