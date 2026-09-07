package com.riskwarning.processing.fact;

import com.riskwarning.common.dto.fact.FactExtractionResponse;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** 解析与三层校验结果。 */
@Getter
public class FactExtractionValidationResult {

    private FactExtractionResponse response;
    private final List<FactValidationError> errors = new ArrayList<>();

    public boolean isValid() {
        return errors.isEmpty();
    }

    public void setResponse(FactExtractionResponse response) {
        this.response = response;
    }

    public void addError(String code, String field, String detail) {
        errors.add(new FactValidationError(code, field, detail));
    }

    public List<String> errorCodes() {
        return errors.stream().map(FactValidationError::getCode).distinct()
                .sorted().collect(Collectors.toList());
    }

    public List<FactValidationError> immutableErrors() {
        return Collections.unmodifiableList(errors);
    }
}
