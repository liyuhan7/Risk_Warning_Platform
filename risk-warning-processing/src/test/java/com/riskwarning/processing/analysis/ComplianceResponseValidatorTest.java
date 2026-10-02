package com.riskwarning.processing.analysis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.analysis.ComplianceTestData.*;

class ComplianceResponseValidatorTest {
    private final ComplianceResponseValidator validator = new ComplianceResponseValidator();
    private ComplianceValidation check(ObjectNode n) { return validator.validate(n.toString(), context(), rules()); }

    @Test void acceptsFalseZeroAndLowConfidenceWithoutThreshold() {
        ObjectNode n = response(); n.put("confidence", 0.001);
        assertTrue(check(n).isValid());
        n.remove("conditionMet"); n.put("indicatorId", "range"); n.put("observedValue", 0); n.put("observedUnit", "day");
        assertTrue(check(n).isValid());
    }
    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "```json\n{}\n```", "{} {}", "{\"confidence\":0.5,\"confidence\":1}", "{\"confidence\":NaN}"})
    void rejectsNonObjectWrapperTrailingAndDuplicateJson(String raw) {
        assertFalse(validator.validate(raw, context(), rules()).isValid());
    }
    @ParameterizedTest
    @ValueSource(strings = {"indicatorId", "evidenceIds", "regulationIds"})
    void rejectsReferencesFromOtherBehavior(String key) {
        ObjectNode n = response();
        if ("indicatorId".equals(key)) n.put(key, "foreign"); else n.putArray(key).add("foreign");
        assertFalse(check(n).isValid());
        assertTrue(check(n).getErrors().stream().anyMatch(e -> e.startsWith(key + ":")));
    }
    @ParameterizedTest
    @ValueSource(strings = {"calculatedScore", "riskLevel", "analysisRunId", "behaviorId", "modelVersion"})
    void rejectsModelOwnedScoreRiskAndScope(String key) {
        ObjectNode n = response(); n.put(key, "forged"); assertFalse(check(n).isValid());
    }
    @Test void rejectsWrongTypesAndNonfiniteNumbers() {
        ObjectNode n = response(); n.put("conditionMet", "false"); assertFalse(check(n).isValid());
        n = response(); n.put("confidence", "0.8"); assertFalse(check(n).isValid());
        n = response(); n.put("confidence", 1.1); assertFalse(check(n).isValid());
        n = response(); n.put("confidence", Double.POSITIVE_INFINITY); assertFalse(check(n).isValid());
        n = response(); n.remove("indicatorId"); assertFalse(check(n).isValid());
    }
    @Test void rejectsMissingUnitWrongUnitAndMixedInputs() {
        ObjectNode n = response(); n.put("observedValue", 1); n.put("observedUnit", "day"); assertFalse(check(n).isValid());
        n.remove("conditionMet"); n.put("indicatorId", "range"); n.remove("observedUnit"); assertFalse(check(n).isValid());
        n.put("observedUnit", "hour"); assertFalse(check(n).isValid());
        n.put("observedUnit", "day"); n.put("observedValue", Double.NaN); assertFalse(check(n).isValid());
    }
    @Test void legalLabelRequiresCurrentRegulationAndGap() {
        ObjectNode n = response(); n.put("complianceStatus", "NON_COMPLIANT"); assertFalse(check(n).isValid());
        n.putArray("regulationIds").add("reg"); assertFalse(check(n).isValid());
        n.put("gapType", "PROHIBITED_ACTION"); assertTrue(check(n).isValid());
        n.put("gapValue", 1); assertFalse(check(n).isValid()); n.put("gapUnit", "count"); assertTrue(check(n).isValid());
        n = response(); n.put("reasoning", "该企业违法"); assertFalse(check(n).isValid());
    }
    @Test void unknownAndMissingMaterialDoNotFabricateInputs() {
        ObjectNode n = response(); n.put("applicable", "UNKNOWN"); assertFalse(check(n).isValid());
        n.put("complianceStatus", "INSUFFICIENT_EVIDENCE"); assertFalse(check(n).isValid());
        n.remove("conditionMet"); assertTrue(check(n).isValid());
        n.put("complianceStatus", "NEEDS_REVIEW"); assertTrue(check(n).isValid());
    }
    @Test void regulationMetadataWithoutOriginalTextCannotSupportLegalConclusion() {
        ObjectNode n = response(); n.put("complianceStatus", "NON_COMPLIANT");
        n.put("gapType", "PROHIBITED_ACTION"); n.putArray("regulationIds").add("reg");
        com.riskwarning.processing.dto.analysis.BehaviorAnalysisContextVO c = context();
        c.getRegulationCandidates().get(0).setContentAvailable(false);
        c.getRegulationCandidates().get(0).setContent(null);
        assertFalse(validator.validate(n.toString(), c, rules()).isValid());
    }
    @Test void zeroIndicatorIsExplicitAndDoesNotChooseFirstCandidate() {
        ObjectNode n = response(); n.putNull("indicatorId"); n.remove("conditionMet");
        n.put("applicable", "NOT_APPLICABLE"); n.put("complianceStatus", "NO_APPLICABLE_CANDIDATE");
        assertTrue(check(n).isValid());
        n.put("complianceStatus", "COMPLIANT"); assertFalse(check(n).isValid());
    }
    @Test void unsupportedRuleCannotInventInputsAndDuplicateEvidenceFails() {
        ObjectNode n = response(); n.put("indicatorId", "binary");
        assertFalse(validator.validate(n.toString(), context(), java.util.Collections.emptyMap()).isValid());
        n.remove("conditionMet"); assertTrue(validator.validate(n.toString(), context(), java.util.Collections.emptyMap()).isValid());
        n.withArray("evidenceIds").add(EVIDENCE.getId()); assertFalse(check(n).isValid());
    }
}
