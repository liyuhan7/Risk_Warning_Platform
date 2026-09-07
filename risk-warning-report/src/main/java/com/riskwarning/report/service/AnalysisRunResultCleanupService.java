package com.riskwarning.report.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.report.repository.IndicatorResultRepository;
import org.springframework.stereotype.Service;

/** 成功切换后清理同一评估中已经被替代的运行结果。 */
@Service
public class AnalysisRunResultCleanupService {

    private final IndicatorResultRepository indicatorResultRepository;
    private final ElasticsearchClient elasticsearchClient;

    public AnalysisRunResultCleanupService(IndicatorResultRepository indicatorResultRepository,
                                           ElasticsearchClient elasticsearchClient) {
        this.indicatorResultRepository = indicatorResultRepository;
        this.elasticsearchClient = elasticsearchClient;
    }

    public void cleanupAfterSuccess(Long assessmentId, String currentRunId) throws Exception {
        indicatorResultRepository.deleteByAssessmentIdAndAnalysisRunIdNot(assessmentId, currentRunId);
        deleteObsoleteDocuments(ElasticSearchConfig.RISK_INDEX, assessmentId, currentRunId);
        deleteObsoleteDocuments("t_behavior", assessmentId, currentRunId);
    }

    private void deleteObsoleteDocuments(String index, Long assessmentId, String currentRunId) throws Exception {
        elasticsearchClient.deleteByQuery(request -> request
                .index(index)
                .query(query -> query.bool(bool -> bool
                        .filter(filter -> filter.term(term -> term.field("assessmentId").value(assessmentId)))
                        .mustNot(excluded -> excluded.term(term -> term.field("analysisRunId").value(currentRunId))))));
    }
}
