package com.riskwarning.processing.service;

import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.dto.BehaviorListVO;
import com.riskwarning.processing.dto.StructuredBehaviorVO;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 结构化事实只读查询。
 *
 * 省略 analysisRunId 时解析该评估最近一次 SUCCEEDED 运行：新运行"写新→校验→切换→清理"，
 * 在途运行期间旧结果仍保留，因此按评估直接查询会混入在途运行的中间数据，
 * 这里与报告侧口径一致地先解析当前有效结果。
 *
 * 显式传入 analysisRunId 时只校验运行归属、不限制状态，供调试与追溯使用；
 * 无论哪种路径，ES 查询都使用三作用域精确匹配，不回退为项目级读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorQueryService {

    private final BehaviorDocumentRepository behaviorDocumentRepository;
    private final AnalysisRunRepository analysisRunRepository;

    public BehaviorListVO listByScope(Long projectId, Long assessmentId, String analysisRunId) {
        validateScope(projectId, assessmentId);
        if (analysisRunId != null && !analysisRunId.trim().isEmpty()) {
            return listForExplicitRun(projectId, assessmentId, analysisRunId.trim());
        }
        return listForCurrentRun(projectId, assessmentId);
    }

    private BehaviorListVO listForExplicitRun(Long projectId, Long assessmentId, String runId) {
        AnalysisRun run = analysisRunRepository
                .findByAnalysisRunIdAndAssessmentIdAndProjectId(runId, assessmentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("analysisRunId 不存在或不属于该评估"));
        return build(run, projectId, assessmentId);
    }

    private BehaviorListVO listForCurrentRun(Long projectId, Long assessmentId) {
        Optional<AnalysisRun> current = analysisRunRepository
                .findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(assessmentId, AnalysisRunStatus.SUCCEEDED);
        if (!current.isPresent()) {
            return BehaviorListVO.builder()
                    .analysisRunId(null)
                    .hasSuccessfulRun(false)
                    .behaviors(Collections.emptyList())
                    .build();
        }
        return build(current.get(), projectId, assessmentId);
    }

    private BehaviorListVO build(AnalysisRun run, Long projectId, Long assessmentId) {
        List<Behavior> behaviors =
                behaviorDocumentRepository.findByScope(projectId, assessmentId, run.getAnalysisRunId());
        log.info("[Behavior Query] projectId={}, assessmentId={}, analysisRunId={}, totalCount={}",
                projectId, assessmentId, run.getAnalysisRunId(), behaviors.size());
        return BehaviorListVO.builder()
                .analysisRunId(run.getAnalysisRunId())
                .hasSuccessfulRun(true)
                .behaviors(behaviors.stream()
                        .map(StructuredBehaviorVO::from)
                        .collect(Collectors.toList()))
                .build();
    }

    private void validateScope(Long projectId, Long assessmentId) {
        if (projectId == null || projectId <= 0 || assessmentId == null || assessmentId <= 0) {
            throw new IllegalArgumentException("projectId 与 assessmentId 必须为正数");
        }
    }
}