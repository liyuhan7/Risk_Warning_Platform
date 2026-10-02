package com.riskwarning.processing.rule;

import com.riskwarning.common.dto.analysis.RuleEvaluationResult;
import com.riskwarning.common.enums.RuleTypeEnum;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class RuleEvaluationContractsTest {
    @Test void validDecisionAndUndecidedWithoutFakeScoreAreAccepted() {
        RuleEvaluationResult result = decision(); RuleEvaluationContracts.validate(result);
        result.setStatus(RuleEvaluationStatus.DECIDED_WITH_CONFLICT); result.setConflict(true); RuleEvaluationContracts.validate(result);
        RuleEvaluationResult unsupported = RuleEvaluationResult.builder().indicatorId("i").status(RuleEvaluationStatus.UNSUPPORTED_RULE)
                .inputHash(RuleCanonicalJson.hash("inputs")).errors(Collections.singletonList("UNREGISTERED")).build();
        RuleEvaluationContracts.validate(unsupported);
        unsupported.setCalculatedScore(0.0); assertThrows(RuleContractException.class, () -> RuleEvaluationContracts.validate(unsupported));
    }
    @Test void invalidScoreThresholdRiskAndConflictAreRejected() {
        RuleEvaluationResult nan = decision(); nan.setCalculatedScore(Double.NaN); assertThrows(RuleContractException.class, () -> RuleEvaluationContracts.validate(nan));
        RuleEvaluationResult r = decision(); r.setRiskLevel(RiskLevelEnum.MEDIUM_RISK); final RuleEvaluationResult risk = r;
        assertThrows(RuleContractException.class, () -> RuleEvaluationContracts.validate(risk));
        RuleEvaluationResult conflict = decision(); conflict.setConflict(true); assertThrows(RuleContractException.class, () -> RuleEvaluationContracts.validate(conflict));
        RuleEvaluationResult threshold = decision(); threshold.setThresholdVersion(null); assertThrows(RuleContractException.class, () -> RuleEvaluationContracts.validate(threshold));
    }
    private RuleEvaluationResult decision() {
        return RuleEvaluationResult.builder().indicatorId("i").ruleType(RuleTypeEnum.BINARY).status(RuleEvaluationStatus.DECIDED)
                .ruleVersion("v1").thresholdVersion("t1").aggregationOperator(AggregationOperator.ANY)
                .inputHash(RuleCanonicalJson.hash("inputs")).decision("NO_RISK").calculatedScore(2.0).maxPossibleScore(2.0)
                .riskTriggered(false).riskLevel(RiskLevelEnum.NO_RISK).conflict(false).build();
    }
}
