package com.riskwarning.processing.service;

import com.riskwarning.common.po.analysis.AnalysisResult;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.processing.repository.AnalysisResultRepository;
import com.riskwarning.processing.repository.IndicatorResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 保证一个 P2 Run 的可发布分析结果与指标结果原子写入。 */
@Service
public class P2ResultPersistenceService {
    private final AnalysisResultRepository analysisResults;
    private final IndicatorResultRepository indicatorResults;

    public P2ResultPersistenceService(AnalysisResultRepository analysisResults,
                                      IndicatorResultRepository indicatorResults) {
        this.analysisResults = analysisResults;
        this.indicatorResults = indicatorResults;
    }

    /** 无决策 Run 只保留已经形成的分析结论，例如证据不足。 */
    @Transactional
    public void saveAnalyses(List<AnalysisResult> analyses) {
        if (analyses.isEmpty()) {
            return;
        }
        analysisResults.saveAll(analyses);
        analysisResults.flush();
    }

    /** 发布前原子保存全 Run 的分析结果与聚合指标结果。 */
    @Transactional
    public void saveSuccess(List<AnalysisResult> analyses, List<IndicatorResult> indicators) {
        analysisResults.saveAll(analyses);
        indicatorResults.saveAll(indicators);
        analysisResults.flush();
        indicatorResults.flush();
    }
}
