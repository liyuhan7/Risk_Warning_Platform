package com.riskwarning.processing.service.scoring;

import com.riskwarning.common.dto.analysis.*;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.IndicatorRule;
import com.riskwarning.common.enums.analysis.AnalysisAuditStatus;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ScoringDatasetClient;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.rule.v2.*;
import com.riskwarning.processing.service.AnalysisContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 真实检索之后执行逐要求事实分析和计分，依据由登记关系补齐。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class ScoringOrchestrator {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.riskwarning.processing.service.scoring.fact.BehaviorFactAnalysisService factAnalysis;
    private final ScoringDatasetClient datasets;
    private final ScoringRuleRegistry registry;
    private final RequirementScoringEngine engine;
    private final RequirementAnalysisService analysis;
    private final ScoringPersistenceService writer;
    private final AnalysisContextService contexts;
    private final ScoringRunSnapshotRepository snapshots;
    private final AnalysisResultRepository results;
    private final RetrievalAuditRepository retrievals;
    private final ScoringReportPreparationService reports;
    private final RequirementEvaluationProjection projection;

    public void process(IndicatorCalculationTaskMessage message, AnalysisScope scope, List<Behavior> behaviors) {
        AnalysisContextVO context = contexts.context(scope.getAssessmentId(), scope.getProjectId(), scope.getAnalysisRunId());
        Map<String, BehaviorAnalysisContextVO> items = context.getItems().stream().collect(Collectors.toMap(BehaviorAnalysisContextVO::getBehaviorId, item -> item));
        ScoringRunSnapshot snapshot = snapshots.findById(scope.getAnalysisRunId()).orElse(null);
        if (snapshot == null) snapshot = writer.freeze(scope, snapshot(scope, items));
        for (Map.Entry<String, IndicatorRule> rule : snapshot.getRuleDefinitions().entrySet()) {
            if (!registry.version().equals(snapshot.getRegistryVersion()) || !ScoringJson.hash(registry.definition(rule.getKey())).equals(ScoringJson.hash(rule.getValue())))
                throw new IllegalStateException("本次运行登记的规则版本已变化，不能换用新规则续算");
            registry.require(rule.getKey(), snapshot.getSourceBundles().get(rule.getKey()));
        }
        boolean behaviorMode = snapshot.getFactExtractionPlan() != null && "BEHAVIOR".equals(snapshot.getFactExtractionPlan().getMode());
        if (behaviorMode && factAnalysis == null) throw new IllegalStateException("按行为事实分析组件不可用");
        final ScoringRunSnapshot frozen = snapshot;
        List<Runnable> factJobs = new ArrayList<>();
        List<Behavior> orderedBehaviors = new ArrayList<>(behaviors); orderedBehaviors.sort(Comparator.comparing(Behavior::getId));
        for (Behavior behavior : orderedBehaviors) {
            BehaviorAnalysisContextVO item = items.get(behavior.getId());
            List<String> targets = new ArrayList<>();
            for (Map.Entry<String, List<String>> pair : snapshot.getIndicatorBehaviors().entrySet())
                if (pair.getValue().contains(behavior.getId())) targets.add(pair.getKey());
            if (item == null || !"SUCCESS".equals(item.getRetrievalStatus()) || !item.isEvidenceComplete() || !item.isSnapshotAvailable()) {
                writer.behaviorComplete(scope, behavior.getId(), AnalysisAuditStatus.INSUFFICIENT_EVIDENCE, "本次行为没有完整检索或原文证据");
                continue;
            }
            if (behaviorMode && !targets.isEmpty()) {
                factJobs.add(() -> factAnalysis.process(scope, frozen, item, targets));
                continue;
            }
            for (String indicator : targets) {
                if (results.findByAnalysisRunIdAndBehaviorIdAndIndicatorId(scope.getAnalysisRunId(), behavior.getId(), indicator).isPresent()) continue;
                writer.analysis(scope, analysis.analyze(scope, item, snapshot.getSourceBundles().get(indicator), snapshot.getRuleDefinitions().get(indicator)));
            }
            writer.behaviorComplete(scope, behavior.getId(), targets.isEmpty() ? AnalysisAuditStatus.NEEDS_REVIEW : AnalysisAuditStatus.ANALYZED,
                    targets.isEmpty() ? "本次候选没有已核验启用的要求规则" : null);
        }
        if (behaviorMode) factAnalysis.execute(factJobs);
        List<AnalysisResult> all = results.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId());
        Map<String, Behavior> byId = behaviors.stream().collect(Collectors.toMap(Behavior::getId, behavior -> behavior));
        Map<String, RetrievalAudit> audits = retrievals.findByAssessmentIdAndAnalysisRunId(scope.getAssessmentId(), scope.getAnalysisRunId()).stream()
                .collect(Collectors.toMap(RetrievalAudit::getBehaviorId, row -> row));
        for (String indicator : snapshot.getRuleDefinitions().keySet()) {
            Map<String, Set<String>> evidence = new TreeMap<>(); Map<String, LocalDateTime> times = new TreeMap<>();
            for (String id : snapshot.getIndicatorBehaviors().get(indicator)) {
                Behavior behavior = byId.get(id);
                evidence.put(id, behavior.getEvidenceIds() == null ? Collections.emptySet() : new HashSet<>(behavior.getEvidenceIds()));
                if (behavior.getBehaviorDate() != null) times.put(id, behavior.getBehaviorDate());
            }
            List<RequirementFinding> findings = new ArrayList<>(); List<AnalysisTrace> traces = new ArrayList<>();
            for (AnalysisResult result : all) if (indicator.equals(result.getIndicatorId())) {
                if (result.getRequirementFindings() != null) findings.addAll(result.getRequirementFindings());
                RetrievalAudit audit = audits.get(result.getBehaviorId());
                traces.add(AnalysisTrace.builder().behaviorId(result.getBehaviorId()).evidenceIds(result.getEvidenceIds()).analysisResult(result)
                        .queryText(audit.getQueryText()).queryTemplateVersion(audit.getQueryTemplateVersion()).filterVersion(audit.getFilterVersion())
                        .filterEnabled(audit.getFilterEnabled()).candidates(audit.getCandidates()).build());
            }
            ScoringSourceBundle source = snapshot.getSourceBundles().get(indicator);
            ScoringDecision decision = engine.evaluate(scope, indicator, source, evidence, findings, times);
            writer.decision(scope, source, snapshot.getRuleDefinitions().get(indicator), decision, traces);
        }
        projection.project(scope);
        reports.prepare(message, scope, false);
    }

    private ScoringRunSnapshot snapshot(AnalysisScope scope, Map<String, BehaviorAnalysisContextVO> contexts) {
        Map<String, Object> manifest = datasets.current(); String version = String.valueOf(manifest.get("datasetVersion"));
        Map<String, ScoringSourceBundle> bundles = new TreeMap<>(); Map<String, IndicatorRule> rules = new TreeMap<>();
        Map<String, List<String>> selection = new TreeMap<>();
        for (BehaviorAnalysisContextVO behavior : contexts.values()) {
            if (behavior.getIndicatorCandidates() == null) continue;
            for (RetrievalCandidateVO candidate : behavior.getIndicatorCandidates()) {
                String id = candidate.getCandidateId();
                if (!candidate.isSnapshotAvailable() || !registry.isEnabled(id)) continue;
                if (!bundles.containsKey(id)) {
                    ScoringSourceBundle source = datasets.bundle(version, id);
                    rules.put(id, registry.require(id, source)); bundles.put(id, source);
                }
                selection.computeIfAbsent(id, ignored -> new ArrayList<>()).add(behavior.getBehaviorId());
            }
        }
        selection.values().forEach(Collections::sort);
        Map<String, Object> identity = new TreeMap<>(); identity.put("scope", scope); identity.put("registryVersion", registry.version());
        identity.put("datasetVersion", version); identity.put("sourceBundles", bundles); identity.put("rules", rules); identity.put("selection", selection);
        ScoringRunSnapshot snapshot = new ScoringRunSnapshot(); snapshot.setAnalysisRunId(scope.getAnalysisRunId());
        snapshot.setProjectId(scope.getProjectId()); snapshot.setAssessmentId(scope.getAssessmentId()); snapshot.setRegistryVersion(registry.version());
        snapshot.setDatasetVersion(version); snapshot.setSnapshotHash(ScoringJson.hash(identity)); snapshot.setSourceBundles(bundles);
        snapshot.setRuleDefinitions(rules); snapshot.setIndicatorBehaviors(selection); snapshot.setCreatedAt(LocalDateTime.now());
        if (factAnalysis != null) {
            snapshot.setFactExtractionPlan(factAnalysis.prepare(snapshot, contexts));
            identity.put("factExtractionPlan", snapshot.getFactExtractionPlan()); snapshot.setSnapshotHash(ScoringJson.hash(identity));
        }
        return snapshot;
    }
}
