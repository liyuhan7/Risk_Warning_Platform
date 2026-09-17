package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.utils.KafkaUtils;
import com.riskwarning.processing.client.RetrievalClient;
import com.riskwarning.processing.config.P2ProcessingProperties;
import com.riskwarning.processing.repository.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class P2DemoProcessingServiceTest {
    private final BehaviorDocumentRepository behaviors = mock(BehaviorDocumentRepository.class);
    private final EvidenceQueryService evidence = mock(EvidenceQueryService.class);
    private final RetrievalClient retrieval = mock(RetrievalClient.class);
    private final RetrievalAuditService audits = mock(RetrievalAuditService.class);
    private final IndicatorResultRepository indicators = mock(IndicatorResultRepository.class);
    private final P2ResultPersistenceService persistence = mock(P2ResultPersistenceService.class);
    private final AnalysisRunNoDecisionService noDecision = mock(AnalysisRunNoDecisionService.class);
    private final KafkaUtils kafka = mock(KafkaUtils.class);
    private final AnalysisScope scope = new AnalysisScope(10L, 20L, "run-p2");

    @Test
    void noCandidatesCompletesWithoutDecisionAndPublishesNothing() {
        Behavior behavior = behavior("behavior-1");
        when(behaviors.findByScope(10L, 20L, "run-p2")).thenReturn(Collections.singletonList(behavior));
        when(retrieval.retrieve(any())).thenReturn(RetrievalBatchResponse.builder()
                .items(Collections.singletonList(RetrievalBatchItem.builder().behaviorId("behavior-1")
                        .status(RetrievalBatchStatus.NO_CANDIDATES).candidates(Collections.emptyList()).build()))
                .build());

        service().process(message(), scope);

        verify(audits).save(argThat(a -> a.getRetrievalStatus().name().equals("NO_CANDIDATES")));
        verify(noDecision).complete(scope);
        verify(persistence).saveAnalyses(Collections.emptyList());
        verify(persistence, never()).saveSuccess(anyList(), anyList());
        verifyNoInteractions(kafka);
    }

    @Test
    void retrievalFailureWritesFailedAuditAndPropagates() {
        Behavior behavior = behavior("behavior-1");
        when(behaviors.findByScope(10L, 20L, "run-p2")).thenReturn(Collections.singletonList(behavior));
        when(retrieval.retrieve(any())).thenThrow(new IllegalStateException("provider timeout api-key=secret"));

        assertThrows(IllegalStateException.class, () -> service().process(message(), scope));

        verify(audits).save(argThat(a -> a.getRetrievalStatus().name().equals("FAILED")
                && a.getErrorMessage().contains("[REDACTED]") && !a.getErrorMessage().contains("secret")));
        verify(noDecision, never()).complete(any());
        verifyNoInteractions(persistence);
        verifyNoInteractions(kafka);
    }

    @Test
    void determinedFixturePersistsResultsBeforePublishingCompletion() throws Exception {
        P2DemoProcessingService.FixtureFile file = new ObjectMapper().readValue(
                fixturePath().toFile(), P2DemoProcessingService.FixtureFile.class);
        P2DemoProcessingService.FixtureFact fact = file.cases.get(0).facts.get(0);
        EvidenceChunk chunk = EvidenceChunk.create(101L, 10L, 20L, "source.docx", 1, 0,
                null, null, fact.enterpriseFact, LocalDateTime.of(2026, 9, 17, 10, 0));
        Behavior behavior = behavior("behavior-1");
        behavior.setEvidenceIds(Collections.singletonList(chunk.getId()));
        when(behaviors.findByScope(10L, 20L, "run-p2")).thenReturn(Collections.singletonList(behavior));
        when(evidence.listByIds(anyList(), eq(10L), eq(20L))).thenReturn(Collections.singletonList(chunk));
        List<RetrievalCandidateSnapshot> candidates = new ArrayList<>();
        candidates.add(snapshot(RetrievalCandidateType.INDICATOR, fact.expectedIndicatorId, "指标", 1));
        int rank = 1;
        for (String regulationId : fact.expectedRegulationIds) {
            candidates.add(snapshot(RetrievalCandidateType.REGULATION, regulationId, "法规", rank++));
        }
        when(retrieval.retrieve(any())).thenReturn(RetrievalBatchResponse.builder()
                .items(Collections.singletonList(RetrievalBatchItem.builder().behaviorId("behavior-1")
                        .status(RetrievalBatchStatus.SUCCESS).candidates(candidates).build()))
                .build());
        when(indicators.findByAssessmentIdAndAnalysisRunIdAndIndicatorEsId(20L, "run-p2",
                fact.expectedIndicatorId)).thenReturn(Optional.empty());

        service().process(message(), scope);

        verify(persistence).saveSuccess(argThat(items -> items.iterator().hasNext()), argThat(items -> {
            Iterator<IndicatorResult> iterator = items.iterator();
            return iterator.hasNext() && "p2_mock_fixture_v1".equals(iterator.next().getUsedCalculationRuleType());
        }));
        verify(kafka).sendMessage(any());
        verify(noDecision, never()).complete(any());
    }

    private RetrievalCandidateSnapshot snapshot(RetrievalCandidateType type, String id, String name, int rank) {
        return RetrievalCandidateSnapshot.builder().name(name).content("内容")
                .retrievalTextHash("hash").indicatorLevel(type == RetrievalCandidateType.INDICATOR ? 1 : null)
                .dimension("产品合规风险").type("定性").maxScore(2.0)
                .result(RetrievalResult.builder().candidateType(type).candidateId(id).rank(rank).score(0.9)
                        .scoreType(type == RetrievalCandidateType.INDICATOR
                                ? ScoreType.COSINE_SIMILARITY : ScoreType.HYBRID)
                        .embeddingModel("BAAI/bge-m3").embeddingVersion("bge-m3@2026-09-16-retrieval-v1")
                        .matchedFilters(Collections.singletonMap("applied", "false"))
                        .behaviorId("behavior-1").analysisRunId("run-p2").assessmentId(20L).build())
                .build();
    }

    private P2DemoProcessingService service() {
        P2ProcessingProperties properties = new P2ProcessingProperties();
        properties.setFixturePath(fixturePath().toString());
        return new P2DemoProcessingService(behaviors, evidence, retrieval, audits, indicators, persistence,
                noDecision, new BehaviorQueryTextBuilder(), properties, kafka, new ObjectMapper());
    }

    private Behavior behavior(String id) {
        return Behavior.builder().id(id).projectId(10L).assessmentId(20L).analysisRunId("run-p2")
                .sourceDocumentId(101L).description("设备召回评估").evidenceIds(Collections.singletonList("e-1")).build();
    }

    private IndicatorCalculationTaskMessage message() {
        return new IndicatorCalculationTaskMessage("message", "time", "trace", 1L, 10L, 20L, "run-p2")
                .withDocuments(Collections.singletonList(new SourceDocumentRef(101L,
                        sourcePath().toString())));
    }

    private Path fixturePath() {
        return repositoryPath("test", "fixtures", "p2", "mock-analysis-results.json");
    }

    private Path sourcePath() {
        return repositoryPath("test", "fixtures", "p0", "CASE-001", "source.docx");
    }

    private Path repositoryPath(String... parts) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("test").resolve("fixtures").resolve("p2"))) {
            current = current.getParent();
        }
        if (current == null) { throw new IllegalStateException("找不到仓库根目录"); }
        Path result = current;
        for (String part : parts) { result = result.resolve(part); }
        return result;
    }
}
