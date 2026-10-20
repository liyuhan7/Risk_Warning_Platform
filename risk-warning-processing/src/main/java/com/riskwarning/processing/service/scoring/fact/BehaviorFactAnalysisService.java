package com.riskwarning.processing.service.scoring.fact;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.FactExtractionPlan;
import com.riskwarning.common.enums.analysis.AnalysisAuditStatus;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.processing.dto.analysis.BehaviorAnalysisContextVO;
import com.riskwarning.processing.repository.AnalysisResultRepository;
import com.riskwarning.processing.service.scoring.ScoringPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

/** 负责按行为抽取与分发，指标评分仍由现有编排在所有行为完成后执行。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class BehaviorFactAnalysisService {
    private final BehaviorFactProperties properties;
    private final BehaviorFactPlanner planner;
    private final BehaviorFactExtractor extractor;
    private final BehaviorFactMapper mapper;
    private final BehaviorFactExecutor executor;
    private final ScoringPersistenceService writer;
    private final AnalysisResultRepository results;

    public FactExtractionPlan prepare(ScoringRunSnapshot snapshot, Map<String, BehaviorAnalysisContextVO> contexts) {
        if (properties.getFactMode() == BehaviorFactProperties.Mode.BEHAVIOR) return planner.prepare(snapshot, contexts);
        FactExtractionPlan legacy = new FactExtractionPlan(); legacy.setMode("INDICATOR"); return legacy;
    }

    public void execute(List<Runnable> jobs) { executor.execute(jobs); }

    public void process(AnalysisScope scope, ScoringRunSnapshot snapshot, BehaviorAnalysisContextVO behavior, List<String> targets) {
        if (!snapshot.getFactExtractionPlan().getBehaviors().containsKey(behavior.getBehaviorId())) {
            writer.behaviorComplete(scope, behavior.getBehaviorId(), AnalysisAuditStatus.NEEDS_REVIEW, "冻结计划没有本行为的有效事实问题"); return;
        }
        boolean complete = true;
        for (String target : targets) complete &= results.findByAnalysisRunIdAndBehaviorIdAndIndicatorId(scope.getAnalysisRunId(), behavior.getBehaviorId(), target).isPresent();
        if (!complete) {
            for (AnalysisResult result : mapper.map(scope, snapshot, behavior, extractor.extract(scope, snapshot.getFactExtractionPlan(), snapshot.getFactExtractionPlan().getBehaviors().get(behavior.getBehaviorId()))))
                writer.analysis(scope, result);
        }
        writer.behaviorComplete(scope, behavior.getBehaviorId(), targets.isEmpty() ? AnalysisAuditStatus.NEEDS_REVIEW : AnalysisAuditStatus.ANALYZED,
                targets.isEmpty() ? "本次候选没有已核验启用的要求规则" : null);
    }
}
