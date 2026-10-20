package com.riskwarning.processing.service.scoring.fact;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.FactExtractionPlan;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import java.util.Map;

/** 事实抽取与模型供应商分离；输出保持问题和证据的请求内短编号。 */
public interface BehaviorFactExtractor {
    Map<String, Answer> extract(AnalysisScope scope, FactExtractionPlan settings, BehaviorPlan behavior);
}
