package com.riskwarning.processing.fact;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Fact Extraction 响应的确定性校验错误。 */
@Getter
@AllArgsConstructor
public class FactValidationError {

    private final String code;
    private final String field;
    private final String detail;
}
