package com.riskwarning.processing.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.processing.dto.analysis.BehaviorAnalysisContextVO;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class CompliancePromptSourceIsolationTest {
    @Test void fullCandidatesRemainButRawScoringAndThresholdSourceNeverEntersPrompt() throws Exception {
        BehaviorAnalysisContextVO context = ComplianceTestData.context();
        context.getIndicatorCandidates().get(0).setSourceRule(new ObjectMapper().readTree(
                "{\"calculationRule\":{\"trueScore\":99},\"riskRule\":{\"secretThreshold\":0.25}}"));
        String json = new CompliancePromptRenderer().contextJson(ComplianceTestData.SCOPE,context,Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap());
        assertFalse(json.contains("sourceRule")); assertFalse(json.contains("trueScore")); assertFalse(json.contains("secretThreshold"));
        assertTrue(json.contains("binary")); assertTrue(json.contains("range"));
        assertNotNull(context.getIndicatorCandidates().get(0).getSourceRule());
    }
}
