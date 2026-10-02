package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class VerifiedRuleRegistryTest {
    @Test void sourceHashIsIndependentOfObjectKeyOrderAndNumericPresentation() throws Exception {
        assertEquals(RuleCanonicalJson.hash(M.readTree("{\"a\":1,\"b\":2.0}")), RuleCanonicalJson.hash(M.readTree("{\"b\":2,\"a\":1.0}")));
    }
    @Test void inverseBinaryAndStructuredRangeAreAcceptedWithoutExpressionExecution() {
        assertEquals(0, load(rule("binary")).require("binary", source("binary")).getTrueScore());
        ObjectNode r = range(); ((ObjectNode)r.get("sourceRule")).put("legacyExpression", "throw new Error('do not execute')");
        r.put("sourceRuleHash", RuleCanonicalJson.hash(r.get("sourceRule")));
        assertEquals(2, load(r).require("range", r.get("sourceRule")).getSegments().size());
    }
    @Test void missingDisabledAndDriftedRulesAreRejected() {
        VerifiedRuleRegistry registry = load(rule("binary"));
        assertEquals("UNSUPPORTED_RULE", assertThrows(RuleContractException.class, () -> registry.require("missing", source("binary"))).getCode());
        assertEquals("SOURCE_RULE_MISSING", assertThrows(RuleContractException.class, () -> registry.require("binary", null)).getCode());
        ObjectNode changed = source("binary"); changed.put("maxScore", 3);
        assertEquals("SOURCE_RULE_HASH_MISMATCH", assertThrows(RuleContractException.class, () -> registry.require("binary", changed)).getCode());
        ObjectNode r = rule("binary"); r.put("enabled", false);
        assertFalse(load(r).isEnabled("binary")); assertThrows(RuleContractException.class, () -> load(r).require("binary", source("binary")));
    }
    @ParameterizedTest
    @ValueSource(strings={"sourceRuleHash", "ruleVersion", "aggregationOperator", "maxScore", "thresholdVersion", "verifiedBy", "verifiedAt", "basis", "riskIntervals"})
    void requiredConfigurationCannotUseDefaults(String field) {
        ObjectNode r = rule("binary"); r.remove(field); assertThrows(RuleContractException.class, () -> load(r));
    }
    @Test void wrongHashUnknownFieldsScalarCoercionAndDuplicateKeysFail() {
        ObjectNode badHash = rule("binary"); badHash.put("sourceRuleHash", String.join("", java.util.Collections.nCopies(64,"0")));
        assertThrows(RuleContractException.class, () -> load(badHash));
        ObjectNode r = rule("binary"); r.put("script", "evil()"); final ObjectNode unknown = r;
        assertThrows(RuleContractException.class, () -> load(unknown));
        ObjectNode coerced = rule("binary"); coerced.put("maxScore", "2"); assertThrows(RuleContractException.class, () -> load(coerced));
        assertThrows(RuleContractException.class, () -> VerifiedRuleRegistry.fromJson("{\"schemaVersion\":\"1.0\",\"schemaVersion\":\"2.0\"}", true));
    }
    @Test void invalidAggregationScoreVerificationAndThresholdFail() {
        ObjectNode r = rule("binary"); r.put("aggregationOperator", "AVG"); assertThrows(RuleContractException.class, () -> load(r));
        ObjectNode score = rule("binary"); score.put("trueScore", 3); assertThrows(RuleContractException.class, () -> load(score));
        ObjectNode time = rule("binary"); time.put("verifiedAt", "yesterday"); assertThrows(RuleContractException.class, () -> load(time));
        ObjectNode thresholds = rule("binary"); ((ObjectNode)thresholds.withArray("riskIntervals").get(0)).put("upper", 2);
        assertThrows(RuleContractException.class, () -> load(thresholds));
        ObjectNode overlaps = rule("binary"); overlaps.withArray("riskIntervals").add(overlaps.withArray("riskIntervals").get(0).deepCopy());
        assertThrows(RuleContractException.class, () -> load(overlaps));
    }
    @Test void rangeOverlapHoleUnsortedOrMixedFieldsFail() {
        for (String variant : new String[]{"overlap", "hole", "unsorted", "mixed"}) {
            ObjectNode r = range(); ObjectNode b = (ObjectNode)r.withArray("segments").get(1);
            if ("overlap".equals(variant)) b.put("lowerInclusive", true);
            if ("hole".equals(variant)) b.put("lower", 2);
            if ("unsorted".equals(variant)) b.put("lower", -1);
            if ("mixed".equals(variant)) r.put("trueScore", 1);
            assertThrows(RuleContractException.class, () -> load(r), variant);
        }
    }
    @Test void testRegistryNeedsExplicitPermissionAndDefaultRegistryIsEmpty() {
        assertThrows(RuleContractException.class, () -> VerifiedRuleRegistry.fromJson(registry(rule("binary")).toString(), false));
        assertFalse(new VerifiedRuleRegistry("classpath:rules/verified-rules-v1.json", false).isEnabled("binary"));
    }
    @Test void returnedMutableCopyCannotChangeFrozenRegistry() {
        VerifiedRuleRegistry registry = load(rule("binary")); registry.require("binary", source("binary")).setTrueScore(2.0);
        assertEquals(0, registry.require("binary", source("binary")).getTrueScore());
    }
}
