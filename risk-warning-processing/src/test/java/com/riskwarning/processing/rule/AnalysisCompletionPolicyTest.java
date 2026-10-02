package com.riskwarning.processing.rule;

import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.riskwarning.processing.service.AnalysisCompletionPolicy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class AnalysisCompletionPolicyTest {
    private final AnalysisCompletionPolicy policy = new AnalysisCompletionPolicy();
    @Test void qualityGateGapIsUncoveredRatherThanHistoricalOrNoRisk() {
        RetrievalAudit gap = audit("a", AnalysisAuditStatus.RECALL_GAP);
        AnalysisCompletionPolicy.Completion out = resolve(Collections.singleton("a"), Collections.singletonList(gap),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false);
        assertEquals(CompletionKind.NONE, out.getSummary().getCompletionKind());
        assertEquals(1, out.getSummary().getRecallGapCount());
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, out.getStatus());
        assertThrows(IllegalArgumentException.class, () -> resolve(Collections.singleton("a"), Collections.singletonList(gap),
                Collections.singletonList(result("a")), Collections.singletonList(evaluated(Collections.singletonList(result("a")))),
                Collections.emptyList(), false));
    }
    @Test void allDecidedIsFullAndCountsIndicatorsRatherThanSemanticRows() {
        List<AnalysisResult> results = Arrays.asList(result("a"), result("b"));
        AnalysisCompletionPolicy.Completion out = resolve(new HashSet<>(Arrays.asList("a","b")),
                Arrays.asList(audit("a", AnalysisAuditStatus.ANALYZED), audit("b", AnalysisAuditStatus.ANALYZED)), results,
                Collections.singletonList(evaluated(results)), Collections.emptyList(), false);
        assertEquals(CompletionKind.FULL, out.getSummary().getCompletionKind()); assertEquals(1, out.getSummary().getDecisionCount());
        assertEquals(2, out.getSummary().getAnalyzedCount()); assertEquals(2, out.getSummary().getConclusionCount());
        assertEquals(AnalysisRunStatus.SUCCEEDED, out.getStatus()); assertTrue(out.isPublishable());
    }
    @Test void conflictKeepsDecisionButMakesPartial() {
        AnalysisResult a = result("a"); a.setConditionMet(true);
        AnalysisCompletionPolicy.Completion out = one(a, evaluated(Collections.singletonList(a)));
        assertEquals(CompletionKind.PARTIAL, out.getSummary().getCompletionKind());
        assertEquals(1, out.getSummary().getConflictCount()); assertEquals(1, out.getSummary().getDecisionCount());
    }
    @Test void unsupportedRuleHasNoDecisionAndNoneIsNotNoRisk() {
        AnalysisCompletionPolicy.Completion out = one(result("a"), unresolved("binary", RuleEvaluationStatus.UNSUPPORTED_RULE));
        assertEquals(CompletionKind.NONE, out.getSummary().getCompletionKind());
        assertEquals(1, out.getSummary().getUnsupportedRuleCount()); assertEquals(0, out.getSummary().getDecisionCount());
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, out.getStatus()); assertFalse(out.isPublishable());
    }
    @Test void oneDecisionAndUncoveredBehaviorIsPartial() {
        AnalysisResult a = result("a"); RetrievalAudit b = audit("b", AnalysisAuditStatus.NO_APPLICABLE_CANDIDATE);
        AnalysisCompletionPolicy.Completion out = resolve(new HashSet<>(Arrays.asList("a","b")), Arrays.asList(audit("a", AnalysisAuditStatus.ANALYZED),b),
                Collections.singletonList(a), Collections.singletonList(evaluated(Collections.singletonList(a))), Collections.emptyList(), false);
        assertEquals(CompletionKind.PARTIAL, out.getSummary().getCompletionKind()); assertEquals(1, out.getSummary().getNoApplicableCandidateCount());
    }
    @Test void noCandidatesAndMissingSnapshotsCannotProduceDecision() {
        RetrievalAudit a = audit("a", AnalysisAuditStatus.NOT_ATTEMPTED); a.setRetrievalStatus(RetrievalAuditStatus.NO_CANDIDATES);
        AnalysisCompletionPolicy.Completion out = resolve(Collections.singleton("a"), Collections.singletonList(a), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false);
        assertEquals(CompletionKind.NONE, out.getSummary().getCompletionKind()); assertEquals(1, out.getSummary().getNoCandidateCount());
        a.setRetrievalStatus(RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE);
        assertEquals(1, resolve(Collections.singleton("a"), Collections.singletonList(a), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false).getSummary().getSnapshotUnavailableCount());
    }
    @Test void twiceMalformedResponseIsNeedsReviewNotProviderFailure() {
        List<AnalysisAttempt> attempts = Arrays.asList(attempt("a",1, AnalysisAttemptStatus.INVALID), attempt("a",2, AnalysisAttemptStatus.INVALID));
        AnalysisCompletionPolicy.Completion out = resolve(Collections.singleton("a"), Collections.singletonList(audit("a", AnalysisAuditStatus.NEEDS_REVIEW)),
                Collections.emptyList(), Collections.emptyList(), attempts, false);
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, out.getStatus());
        assertEquals(1, out.getSummary().getNeedsReviewCount()); assertEquals(CompletionKind.NONE, out.getSummary().getCompletionKind());
    }
    @Test void repairedResponseAndValidRuleCanFinishFully() {
        AnalysisResult a = result("a");
        AnalysisCompletionPolicy.Completion out = resolve(Collections.singleton("a"), Collections.singletonList(audit("a", AnalysisAuditStatus.ANALYZED)),
                Collections.singletonList(a), Collections.singletonList(evaluated(Collections.singletonList(a))),
                Arrays.asList(attempt("a",1,AnalysisAttemptStatus.INVALID),attempt("a",2,AnalysisAttemptStatus.VALID)), false);
        assertEquals(CompletionKind.FULL, out.getSummary().getCompletionKind());
    }
    @Test void providerFailureOverridesExistingDecisionsAndDoesNotPublishOrClaimFull() {
        AnalysisResult a = result("a");
        AnalysisCompletionPolicy.Completion out = resolve(new HashSet<>(Arrays.asList("a","b")), Collections.singletonList(audit("a", AnalysisAuditStatus.ANALYZED)),
                Collections.singletonList(a), Collections.singletonList(evaluated(Collections.singletonList(a))),
                Collections.singletonList(attempt("b",1,AnalysisAttemptStatus.PROVIDER_FAILED)), false);
        assertEquals(AnalysisRunStatus.FAILED, out.getStatus()); assertNull(out.getSummary().getCompletionKind());
        assertEquals(1, out.getSummary().getDecisionCount()); assertFalse(out.isPublishable());
    }
    @Test void retrievalAndExecutionFailureAreWholeRunFailures() {
        RetrievalAudit a = audit("a", AnalysisAuditStatus.NOT_ATTEMPTED); a.setRetrievalStatus(RetrievalAuditStatus.FAILED);
        assertEquals(AnalysisRunStatus.FAILED, resolve(Collections.singleton("a"), Collections.singletonList(a), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false).getStatus());
        assertEquals(AnalysisRunStatus.FAILED, resolve(Collections.singleton("a"), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), true).getStatus());
    }
    @Test void insufficientAndRuleReviewCountsAreDeduplicatedByBehavior() {
        AnalysisResult a = result("a"); a.setComplianceStatus(ComplianceStatus.INSUFFICIENT_EVIDENCE);
        AnalysisCompletionPolicy.Completion out = resolve(Collections.singleton("a"), Collections.singletonList(audit("a", AnalysisAuditStatus.INSUFFICIENT_EVIDENCE)),
                Collections.singletonList(a), Collections.singletonList(unresolved("binary", RuleEvaluationStatus.NEEDS_REVIEW)), Collections.emptyList(), false);
        assertEquals(1, out.getSummary().getInsufficientEvidenceCount()); assertEquals(1, out.getSummary().getNeedsReviewCount());
        assertEquals(CompletionKind.NONE, out.getSummary().getCompletionKind());
    }
    @Test void missingPendingDuplicateAndForeignRecordsCannotClaimCompletion() {
        assertThrows(IllegalArgumentException.class, () -> resolve(Collections.singleton("a"), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false));
        assertThrows(IllegalArgumentException.class, () -> resolve(Collections.singleton("a"), Collections.singletonList(audit("a", AnalysisAuditStatus.WAITING_ANALYSIS)), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false));
        assertThrows(IllegalArgumentException.class, () -> resolve(Collections.singleton("a"), Arrays.asList(audit("a", AnalysisAuditStatus.ANALYZED),audit("a", AnalysisAuditStatus.ANALYZED)), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false));
        RetrievalAudit foreign = audit("a", AnalysisAuditStatus.NEEDS_REVIEW); foreign.setProjectId(99L);
        assertThrows(IllegalArgumentException.class, () -> resolve(Collections.singleton("a"), Collections.singletonList(foreign), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false));
        assertThrows(IllegalArgumentException.class, () -> one(result("a"), null));
    }
    @Test void auditCannotClaimDecidedWithMissingOrDifferentFinalDecision() {
        RuleEvaluationAudit r = evaluated(Collections.singletonList(result("a"))); r.setCalculationDetails(null);
        assertThrows(IllegalArgumentException.class, () -> one(result("a"), r));
        r.setCalculationDetails(evaluated(Collections.singletonList(result("a"))).getCalculationDetails()); r.setInputHash("bad");
        assertThrows(IllegalArgumentException.class, () -> one(result("a"), r));
    }
    @Test void insufficientSemanticRowsCannotBackValidRuleDecision() {
        AnalysisResult a = result("a"); RuleEvaluationAudit valid = evaluated(Collections.singletonList(a));
        a.setComplianceStatus(ComplianceStatus.INSUFFICIENT_EVIDENCE);
        assertThrows(IllegalArgumentException.class, () -> one(a, valid));
    }
    @Test void completedAnalysisRequiresSuccessfulRetrieval() {
        AnalysisResult a = result("a"); RetrievalAudit retrieval = audit("a", AnalysisAuditStatus.ANALYZED);
        retrieval.setRetrievalStatus(RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE);
        assertThrows(IllegalArgumentException.class, () -> resolve(Collections.singleton("a"),Collections.singletonList(retrieval),
                Collections.singletonList(a),Collections.singletonList(evaluated(Collections.singletonList(a))),Collections.emptyList(),false));
    }
    @Test void historicalRunRemainsTerminalAndPolicyDoesNotMutateEntities() {
        AnalysisRun historical = AnalysisRun.start(SCOPE, java.time.LocalDateTime.of(2026,9,29,0,0));
        historical.completeWithoutDecision(java.time.LocalDateTime.of(2026,9,29,1,0));
        one(result("a"), evaluated(Collections.singletonList(result("a"))));
        assertEquals(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, historical.getStatus());
        assertThrows(IllegalStateException.class, () -> historical.succeed(java.time.LocalDateTime.now()));
    }
    private AnalysisCompletionPolicy.Completion one(AnalysisResult result, RuleEvaluationAudit rule) {
        return resolve(Collections.singleton("a"), Collections.singletonList(audit("a", AnalysisAuditStatus.ANALYZED)), Collections.singletonList(result),
                rule == null ? Collections.emptyList() : Collections.singletonList(rule), Collections.emptyList(), false);
    }
    private AnalysisCompletionPolicy.Completion resolve(Set<String> behaviors, List<RetrievalAudit> audits, List<AnalysisResult> results, List<RuleEvaluationAudit> rules, List<AnalysisAttempt> attempts, boolean failed) {
        return policy.resolve(SCOPE, behaviors, audits, results, rules, attempts, failed);
    }
    private static RetrievalAudit audit(String behavior, AnalysisAuditStatus status) {
        return RetrievalAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").behaviorId(behavior)
                .retrievalStatus(RetrievalAuditStatus.SUCCESS).analysisStatus(status).build();
    }
    private static AnalysisAttempt attempt(String behavior, int number, AnalysisAttemptStatus status) {
        return AnalysisAttempt.builder().projectId(1L).assessmentId(2L).analysisRunId("run").behaviorId(behavior).attemptNo(number).status(status).build();
    }
    private static RuleEvaluationAudit evaluated(List<AnalysisResult> rows) {
        RuleInputAssembler assembler = new RuleInputAssembler(load(rule("binary")));
        RuleInputAssembler.PreparedInputs p = assembler.prepare(SCOPE,"binary",rows,source("binary"),null);
        RuleEvaluationResult decision = new DeterministicRuleEngine(assembler,new BinaryRuleCalculator(assembler),new RangeRuleCalculator(assembler),new RiskThresholdEvaluator()).evaluate(SCOPE,p,source("binary")).getDecision();
        return ruleAudit(decision);
    }
    private static RuleEvaluationAudit unresolved(String id, RuleEvaluationStatus status) {
        return ruleAudit(RuleEvaluationResult.builder().indicatorId(id).status(status).inputHash(String.join("", Collections.nCopies(64,"a")))
                .errors(Collections.singletonList("明确未决原因")).build());
    }
    private static RuleEvaluationAudit ruleAudit(RuleEvaluationResult decision) {
        return RuleEvaluationAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").indicatorId(decision.getIndicatorId())
                .status(decision.getStatus()).inputHash(decision.getInputHash()).ruleVersion(decision.getRuleVersion()).thresholdVersion(decision.getThresholdVersion())
                .aggregationOperator(decision.getAggregationOperator() == null ? null : decision.getAggregationOperator().name())
                .conflict(Boolean.TRUE.equals(decision.getConflict())).calculationDetails(IndicatorResultDetail.builder()
                        .ruleCalculation(RuleCalculationDetail.builder().finalDecision(decision).build()).build()).build();
    }
}
