package com.riskwarning.processing.service.scoring;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.po.analysis.RequirementEvaluation;
import com.riskwarning.processing.repository.RequirementEvaluationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;

/** 要求结果的 ES 查询副本；SQL 提交后按稳定编号覆盖写入，重试不会生成重复文档。 */
@Service
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class RequirementEvaluationProjection {
    private final ElasticsearchClient client;
    private final RequirementEvaluationRepository rows;
    private final String index;

    public RequirementEvaluationProjection(ElasticsearchClient client, RequirementEvaluationRepository rows,
            @Value("${analysis.rules.evaluation-index:t_requirement_evaluation}") String index) {
        this.client = client; this.rows = rows; this.index = index;
    }

    public void project(AnalysisScope scope) {
        List<RequirementEvaluation> evaluations = rows.findByProjectIdAndAssessmentIdAndAnalysisRunId(scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId());
        if (evaluations.isEmpty()) return;
        try {
            if (!client.indices().exists(request -> request.index(index)).value())
                client.indices().create(request -> request.index(index).mappings(mapping -> mapping
                        .properties("analysisRunId", field -> field.keyword(value -> value))
                        .properties("behaviorId", field -> field.keyword(value -> value))
                        .properties("indicatorId", field -> field.keyword(value -> value))
                        .properties("requirementId", field -> field.keyword(value -> value))
                        .properties("regulationId", field -> field.keyword(value -> value))
                        .properties("judgment", field -> field.keyword(value -> value))
                        .properties("projectId", field -> field.long_(value -> value))
                        .properties("assessmentId", field -> field.long_(value -> value))));
            BulkResponse response = client.bulk(request -> {
                request.index(index);
                for (RequirementEvaluation row : evaluations) request.operations(operation -> operation.index(item -> item.id(row.getId()).document(document(row))));
                return request;
            });
            if (response.errors() || response.items().size() != evaluations.size()) throw new IllegalStateException("要求明细未全部写入 ES");
        } catch (IOException failure) { throw new IllegalStateException("要求明细 ES 投影失败", failure); }
    }

    private Map<String, Object> document(RequirementEvaluation row) {
        Map<String, Object> out = new TreeMap<>();
        out.put("projectId", row.getProjectId()); out.put("assessmentId", row.getAssessmentId()); out.put("analysisRunId", row.getAnalysisRunId());
        out.put("behaviorId", row.getBehaviorId()); out.put("indicatorId", row.getIndicatorId()); out.put("requirementId", row.getRequirementId());
        out.put("regulationId", row.getRegulationId()); out.put("judgment", row.getJudgment().name());
        out.put("satisfaction", row.getSatisfaction()); out.put("earned", row.getEarned()); out.put("maximum", row.getMaximum());
        out.put("observedValue", row.getObservedValue()); out.put("observedUnit", row.getObservedUnit()); out.put("conditionMet", row.getConditionMet());
        out.put("gapType", row.getGapType() == null ? null : row.getGapType().name()); out.put("gapValue", row.getGapValue()); out.put("gapUnit", row.getGapUnit());
        out.put("evidenceIds", row.getEvidenceIds()); out.put("reason", row.getReason()); out.put("conflict", row.isConflict());
        out.put("inputHash", row.getInputHash()); out.put("sourceBundleHash", row.getSourceBundleHash()); out.put("ruleVersion", row.getRuleVersion());
        return out;
    }
}
