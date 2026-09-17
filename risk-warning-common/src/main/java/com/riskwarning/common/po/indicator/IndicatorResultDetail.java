package com.riskwarning.common.po.indicator;

import com.riskwarning.common.dto.analysis.AnalysisTrace;
import com.riskwarning.common.dto.analysis.FixtureDescriptor;
import com.riskwarning.common.po.risk.RelatedIndicator;
import com.riskwarning.common.po.risk.RelatedRegulation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorResultDetail {
    @Builder.Default
    private String schemaVersion = "1.0";
    private String analysisMode;
    private FixtureDescriptor fixture;
    private List<RelatedIndicator> relatedIndicators;
    private List<AnalysisTrace> traces;
}
