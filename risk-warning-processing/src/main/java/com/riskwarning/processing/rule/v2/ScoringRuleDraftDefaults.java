package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import lombok.Data;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.Map;

/** 起草时使用的业务默认权重，实际评分只使用核验后登记的权重。 */
@Data
public class ScoringRuleDraftDefaults {
    private String profileVersion;
    private Map<Direction, Severity> defaultSeverity;
    private Map<Direction, Map<Severity, BigDecimal>> weights;

    public static ScoringRuleDraftDefaults firstVersion() {
        try (InputStream input = ScoringRuleDraftDefaults.class.getResourceAsStream("/rules/scoring-draft-defaults-v1.json")) {
            return read(input);
        } catch (Exception failure) { throw new IllegalArgumentException("规则起草默认参数不可用", failure); }
    }

    public static ScoringRuleDraftDefaults load(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) { return read(input); }
    }

    public Severity severity(Direction direction) { return defaultSeverity.get(direction); }
    public BigDecimal weight(Direction direction, Severity severity) { return weights.get(direction).get(severity); }

    private static ScoringRuleDraftDefaults read(InputStream input) throws Exception {
        ScoringRuleDraftDefaults defaults = new ObjectMapper().readValue(input, ScoringRuleDraftDefaults.class);
        if (defaults.profileVersion == null || defaults.profileVersion.trim().isEmpty()) throw new IllegalArgumentException("默认参数缺少版本");
        for (Direction direction : Direction.values()) {
            if (defaults.defaultSeverity == null || defaults.defaultSeverity.get(direction) == null
                    || defaults.weights == null || defaults.weights.get(direction) == null)
                throw new IllegalArgumentException("默认参数缺少方向或严重度映射");
            for (Severity severity : Severity.values()) {
                BigDecimal weight = defaults.weight(direction, severity);
                if (weight == null || weight.signum() <= 0) throw new IllegalArgumentException("默认权重必须为正数");
            }
        }
        return defaults;
    }
}
