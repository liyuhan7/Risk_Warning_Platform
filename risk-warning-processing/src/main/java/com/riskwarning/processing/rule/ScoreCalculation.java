package com.riskwarning.processing.rule;

import lombok.Value;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 计分阶段的中间结果；风险与语义冲突须在显式阈值执行后判断。 */
@Value
public class ScoreCalculation {
    Boolean conditionMet;
    Double observedValue;
    double calculatedScore;
    double maxPossibleScore;
    List<String> contributingResultIds;
    String inputHash;

    public ScoreCalculation(Boolean conditionMet, Double observedValue, double calculatedScore,
                            double maxPossibleScore, List<String> contributingResultIds, String inputHash) {
        this.conditionMet = conditionMet;
        this.observedValue = observedValue;
        this.calculatedScore = calculatedScore;
        this.maxPossibleScore = maxPossibleScore;
        this.contributingResultIds = Collections.unmodifiableList(new ArrayList<>(contributingResultIds));
        this.inputHash = inputHash;
    }
}
