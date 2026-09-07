package com.riskwarning.common.dto.fact;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次事实抽取的可审计调用信息。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FactExtractionCallMetadata {

    private String modelId;
    private String promptVersion;
    private int batchCount;
    private int providerCallCount;
    private long durationMillis;
}
