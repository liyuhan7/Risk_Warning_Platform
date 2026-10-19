package com.riskwarning.processing.rule.v2;

import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.dto.scoring.RequirementFinding.ConditionValue;
import com.riskwarning.processing.rule.RuleContractException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** 封闭条件树的三值求值；未知不视为假，方向不参与条件取反。 */
@Component
public class UniversalConditionEvaluator {
    public enum Truth { TRUE, FALSE, UNKNOWN }

    public Truth evaluate(Condition condition, ValueType inherited, Map<String, ConditionValue> values) {
        if (condition.getNotOf() != null) {
            Truth child = evaluate(condition.getNotOf(), inherited, values);
            return child == Truth.UNKNOWN ? child : child == Truth.TRUE ? Truth.FALSE : Truth.TRUE;
        }
        List<Condition> children = condition.getAllOf() != null ? condition.getAllOf() : condition.getAnyOf();
        if (children != null) {
            boolean all = condition.getAllOf() != null;
            boolean unknown = false;
            for (Condition child : children) {
                Truth value = evaluate(child, inherited, values);
                if (all && value == Truth.FALSE || !all && value == Truth.TRUE) return value;
                unknown |= value == Truth.UNKNOWN;
            }
            return unknown ? Truth.UNKNOWN : all ? Truth.TRUE : Truth.FALSE;
        }
        ConditionValue value = values.get(condition.getConditionId());
        if (value == null) return Truth.UNKNOWN;
        ValueType type = condition.getValueType() == null ? inherited : condition.getValueType();
        if (type == ValueType.BOOLEAN) {
            if (value.getObservedValue() != null || value.getObservedUnit() != null)
                throw failure("布尔事实不得携带数值或单位");
            if (value.getConditionMet() == null) return Truth.UNKNOWN;
            boolean satisfied = condition.getOperator() == Operator.IS_TRUE ? value.getConditionMet() : !value.getConditionMet();
            return satisfied ? Truth.TRUE : Truth.FALSE;
        }
        if (value.getConditionMet() != null) throw failure("数值事实不得携带布尔值");
        if (value.getObservedValue() == null) return Truth.UNKNOWN;
        if (!Objects.equals(condition.getUnit(), value.getObservedUnit())) throw failure("事实单位与规则单位不一致");
        return number(condition, value.getObservedValue()) ? Truth.TRUE : Truth.FALSE;
    }

    public static boolean number(Condition condition, BigDecimal value) {
        int lower = value.compareTo(condition.getOperand());
        switch (condition.getOperator()) {
            case EQ: return lower == 0;
            case NE: return lower != 0;
            case LT: return lower < 0;
            case LE: return lower <= 0;
            case GT: return lower > 0;
            case GE: return lower >= 0;
            case BETWEEN:
                int upper = value.compareTo(condition.getUpperOperand());
                return (lower > 0 || lower == 0 && condition.getLowerInclusive())
                        && (upper < 0 || upper == 0 && condition.getUpperInclusive());
            default: throw failure("不是数值比较运算符");
        }
    }

    private static RuleContractException failure(String message) { return new RuleContractException("INVALID_INPUT", message); }
}
