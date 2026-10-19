package com.riskwarning.report.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;

/** 随报告保存的维度权重；已生成报告读取自己的配置，不随资源更新而改分。 */
@Data
public class ReportAggregationProfile {
    private String profileVersion;
    private Map<String, BigDecimal> dimensionWeights;

    static ReportAggregationProfile firstVersion() {
        try (InputStream input = ReportAggregationProfile.class.getResourceAsStream("/scoring/report-aggregation-v1.json")) {
            return new ObjectMapper().readValue(input, ReportAggregationProfile.class);
        } catch (Exception failure) { throw new IllegalStateException("报告维度权重配置不可用", failure); }
    }
}
