package com.riskwarning.processing.service;

import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 最新运行的只读聚合；空候选与无决策均不代表无风险。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalysisOverviewService {
    private final AssessmentRepository assessments;
    private final ProjectRepository projects;
    private final AnalysisRunRepository runs;
    private final AnalysisResultRepository results;
    private final RetrievalAuditRepository audits;
    private final BehaviorDocumentRepository behaviors;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 校验评估归属后读取最新运行，聚合该运行的结论、候选与审计。
     * 行为描述读取失败时保留权威分析记录，不回退旧报告。
     */
    @Transactional(readOnly = true)
    public AnalysisOverviewVO overview(Long assessmentId, Long projectId) {
        return overview(assessmentId, projectId, null);
    }

    /** 指定 Run 时必须同时验证项目和评估归属，不回退到其他运行。 */
    @Transactional(readOnly = true)
    public AnalysisOverviewVO overview(Long assessmentId, Long projectId, String analysisRunId) {
        if (projectId == null || projectId <= 0 || assessmentId == null || assessmentId <= 0) {
            throw new IllegalArgumentException("projectId 与 assessmentId 必须为正数");
        }
        Assessment assessment = assessments.findById(assessmentId)
                .orElseThrow(() -> new NoSuchElementException("评估不存在"));
        if (!projectId.equals(assessment.getProjectId())) {
            throw new SecurityException("项目与评估归属不匹配");
        }
        AnalysisOverviewVO vo = AnalysisOverviewVO.builder().assessmentId(assessmentId).projectId(projectId)
                .projectName(projects.findById(projectId).map(p -> p.getName()).orElse(null))
                .assessmentDate(time(assessment.getAssessmentDate())).displayStatus("NOT_STARTED")
                .summary(new AnalysisSummaryVO()).behaviorGroups(new ArrayList<>()).build();
        Optional<AnalysisRun> latest = hasText(analysisRunId)
                ? Optional.of(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(analysisRunId, assessmentId, projectId)
                    .orElseThrow(() -> new NoSuchElementException("指定运行不存在或不属于本次评估")))
                : runs.findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(assessmentId, projectId);
        if (!latest.isPresent()) { return vo; }
        AnalysisRun run = latest.get();
        vo.setRun(AnalysisRunSummaryVO.builder().analysisRunId(run.getAnalysisRunId()).status(run.getStatus().name())
                .startedAt(time(run.getStartedAt())).finishedAt(time(run.getFinishedAt())).build());
        List<RetrievalAudit> auditRows = audits.findByAssessmentIdAndAnalysisRunId(assessmentId, run.getAnalysisRunId());
        List<AnalysisResult> resultRows = results.findByAssessmentIdAndAnalysisRunId(assessmentId, run.getAnalysisRunId());
        vo.getRun().setAnalysisMode(AnalysisModeResolver.resolve(resultRows, auditRows));
        Map<String, Behavior> descriptions = new HashMap<>();
        // ES 不可用只影响行为描述，PostgreSQL 的审计与结论仍可回放。
        try {
            for (Behavior b : behaviors.findByScope(projectId, assessmentId, run.getAnalysisRunId())) { descriptions.put(b.getId(), b); }
        } catch (RuntimeException e) {
            log.warn("分析概览行为描述不可用: assessmentId={}, run={}", assessmentId, run.getAnalysisRunId(), e);
        }
        Map<String, BehaviorAnalysisGroupVO> groups = new TreeMap<>();
        descriptions.forEach((id, behavior) -> groups.put(id, group(id, behavior)));
        for (RetrievalAudit audit : auditRows) {
            BehaviorAnalysisGroupVO group = groups.computeIfAbsent(audit.getBehaviorId(), id -> group(id, descriptions.get(id)));
            group.setRetrievalAudit(audit(audit));
            if (group.getRetrievalAudit().isCandidateTruncated()) { vo.setTruncated(true); }
        }
        for (AnalysisResult result : resultRows) {
            BehaviorAnalysisGroupVO group = groups.computeIfAbsent(result.getBehaviorId(), id -> group(id, descriptions.get(id)));
            // 名称匹配使用完整快照，不能被展示窗口截断影响。
            Map<String, String> names = new HashMap<>();
            auditRows.stream().filter(a -> Objects.equals(a.getBehaviorId(), result.getBehaviorId()))
                    .flatMap(a -> safe(a.getCandidates()).stream()).filter(s -> s != null && s.getResult() != null)
                    .forEach(s -> names.put(s.getResult().getCandidateType()+":"+s.getResult().getCandidateId(), s.getName()));
            AnalysisConclusionVO c = new AnalysisConclusionVO();
            org.springframework.beans.BeanUtils.copyProperties(result, c);
            c.setResultId(result.getId()); c.setApplicable(value(result.getApplicable()));
            c.setComplianceStatus(value(result.getComplianceStatus())); c.setGapType(value(result.getGapType()));
            c.setAnalyzedAt(time(result.getAnalyzedAt())); c.setMock("mock-fixture-v1".equals(result.getModelVersion()));
            c.setEvidenceIds(safe(result.getEvidenceIds())); c.setRegulationIds(safe(result.getRegulationIds()));
            c.setIndicatorName(names.get("INDICATOR:"+result.getIndicatorId()));
            c.setRegulationNames(safe(result.getRegulationIds()).stream().map(id -> names.get("REGULATION:"+id)).collect(Collectors.toList()));
            group.getConclusions().add(c);
        }
        groups.values().forEach(g -> g.getConclusions().sort(Comparator.comparing(AnalysisConclusionVO::getResultId)));
        vo.setBehaviorGroups(new ArrayList<>(groups.values()));
        AnalysisSummaryVO summary = vo.getSummary();
        summary.setBehaviorCount(Math.max(descriptions.size(), groups.size())); summary.setConclusionCount(resultRows.size());
        summary.setDecisionCount((int) resultRows.stream().filter(r -> r.getComplianceStatus() != null && !"INSUFFICIENT_EVIDENCE".equals(value(r.getComplianceStatus()))).count());
        for (RetrievalAudit a : auditRows) {
            String retrieval = value(a.getRetrievalStatus()), analysis = value(a.getAnalysisStatus());
            if ("SUCCESS".equals(retrieval)) summary.setRetrievalSuccessCount(summary.getRetrievalSuccessCount()+1);
            if ("NO_CANDIDATES".equals(retrieval)) summary.setNoCandidateCount(summary.getNoCandidateCount()+1);
            if ("FAILED".equals(retrieval)) summary.setRetrievalFailedCount(summary.getRetrievalFailedCount()+1);
            if ("SNAPSHOT_UNAVAILABLE".equals(retrieval)) summary.setSnapshotUnavailableCount(summary.getSnapshotUnavailableCount()+1);
            if ("WAITING_P3".equals(analysis)) summary.setWaitingForAnalysisCount(summary.getWaitingForAnalysisCount()+1);
            if ("NOT_ATTEMPTED".equals(analysis)) summary.setNotAttemptedCount(summary.getNotAttemptedCount()+1);
            if ("NOT_DEMO_INPUT".equals(analysis)) summary.setNotDemoInputCount(summary.getNotDemoInputCount()+1);
            if ("RECALL_GAP".equals(analysis)) summary.setRecallGapCount(summary.getRecallGapCount()+1);
            if ("INSUFFICIENT_EVIDENCE".equals(analysis)) summary.setInsufficientEvidenceCount(summary.getInsufficientEvidenceCount()+1);
        }
        String status = run.getStatus().name();
        if ("SUCCEEDED".equals(status)) status = "COMPLETED_WITH_DECISION";
        if ("COMPLETED_WITHOUT_DECISION".equals(status) && !auditRows.isEmpty()
                && auditRows.stream().allMatch(a -> a.getRetrievalStatus()
                    == com.riskwarning.common.enums.analysis.RetrievalAuditStatus.NO_CANDIDATES)) { status = "NO_CANDIDATES"; }
        vo.setDisplayStatus(status);
        return vo;
    }

    private BehaviorAnalysisGroupVO group(String id, Behavior b) {
        String description = b == null ? null : b.getDescription();
        if (b != null && (description == null || description.trim().isEmpty())) {
            description = Arrays.asList(b.getSubject(), b.getAction(), b.getObject()).stream()
                    .filter(Objects::nonNull).collect(Collectors.joining(" "));
        }
        return BehaviorAnalysisGroupVO.builder().behaviorId(id).behaviorDescription(description)
                .behaviorType(b == null ? null : b.getType()).behaviorDimension(b == null ? null : b.getDimension())
                .subject(b == null ? null : b.getSubject()).action(b == null ? null : b.getAction())
                .object(b == null ? null : b.getObject()).quantitativeData(b == null ? null : b.getQuantitativeData())
                .quantitativeUnit(b == null ? null : b.getQuantitativeUnit()).confidence(b == null ? null : b.getConfidence())
                .sourceDocumentId(b == null ? null : b.getSourceDocumentId())
                .evidenceIds(b == null ? Collections.emptyList() : safe(b.getEvidenceIds()))
                .conclusions(new ArrayList<>()).build();
    }

    private RetrievalAuditVO audit(RetrievalAudit row) {
        RetrievalAuditVO vo = new RetrievalAuditVO();
        org.springframework.beans.BeanUtils.copyProperties(row, vo);
        vo.setRetrievalStatus(value(row.getRetrievalStatus())); vo.setAnalysisStatus(value(row.getAnalysisStatus()));
        vo.setSnapshotAvailable(row.getCandidates() != null);
        vo.setCandidateTotal(safe(row.getCandidates()).size()); vo.setCandidateTruncated(vo.getCandidateTotal() > 50);
        vo.setCandidates(safe(row.getCandidates()).stream().limit(50)
                .map(RetrievalCandidateVoMapper::toVo).collect(Collectors.toList()));
        return vo;
    }
    private static String value(Enum<?> value) { return value == null ? null : value.name(); }
    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private static String time(LocalDateTime value) { return value == null ? null : TIME.format(value); }
    private static <T> List<T> safe(List<T> values) { return values == null ? Collections.emptyList() : values; }
}
