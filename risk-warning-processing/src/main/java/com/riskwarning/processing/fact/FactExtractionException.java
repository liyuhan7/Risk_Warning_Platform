package com.riskwarning.processing.fact;

import com.riskwarning.common.dto.fact.FactExtractionResult;

/** 模型响应连续两次未通过契约校验，当前运行必须失败。 */
public class FactExtractionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FactExtractionResult result;

    public FactExtractionException(String message, FactExtractionResult result) {
        super(message);
        this.result = result;
    }

    public FactExtractionResult getResult() {
        return result;
    }
}
