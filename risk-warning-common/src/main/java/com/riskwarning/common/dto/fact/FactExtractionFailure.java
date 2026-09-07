package com.riskwarning.common.dto.fact;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 单个抽取批次的失败明细，供日志、诊断和失败终态记录使用。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FactExtractionFailure {

    private int batchIndex;
    private List<String> errorCodes;
    private String responseExcerpt;
}
