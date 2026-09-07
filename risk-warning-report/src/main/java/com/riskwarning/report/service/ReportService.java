package com.riskwarning.report.service;

import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.report.entity.vo.general.AssessmentDetailVO;
import com.riskwarning.report.entity.vo.indicator.IndicatorDistributionVO;
import com.riskwarning.report.entity.vo.risk.RiskVO;

import java.util.List;

public interface ReportService {

    IndicatorDistributionVO assembleIndicatorResult(Assessment assessment);

    /** 供运行完成阶段按指定 Run 组装尚未切换的结果。 */
    IndicatorDistributionVO assembleIndicatorResult(Assessment assessment, String analysisRunId);

    List<RiskVO> assembleRisk(Long assessmentId);

    AssessmentDetailVO assembleGeneral(Assessment assessment);

    /**
     * 使用内存中的风险列表构建汇总（用于聚合阶段，避免 ES 刷新延迟导致查不到刚写入的 Risk）
     */
    AssessmentDetailVO assembleGeneral(Assessment assessment, List<Risk> risks);
}
