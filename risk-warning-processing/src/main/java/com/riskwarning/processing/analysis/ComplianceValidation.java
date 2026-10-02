package com.riskwarning.processing.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;
import java.util.List;

/** 校验输出与精确字段错误，用于一次修复及审计。 */
@Value
public class ComplianceValidation {
    JsonNode response;
    List<String> errors;
    public boolean isValid() { return errors.isEmpty(); }
}
