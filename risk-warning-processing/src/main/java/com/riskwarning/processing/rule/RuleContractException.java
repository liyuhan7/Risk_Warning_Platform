package com.riskwarning.processing.rule;

/** 显式规则拒绝原因，由评估层映射到审计状态。 */
public class RuleContractException extends IllegalArgumentException {
    private final String code;
    public RuleContractException(String code, String message) { super(message); this.code = code; }
    public String getCode() { return code; }
}
