package com.riskwarning.common.dto.retrieval;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndicatorRuleSourceSnapshotTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void capturesOnlyRuleIdentityAndDeepCopiesOriginalSource() {
        Map<String,Object> calculation = new HashMap<>(); calculation.put("expression", "原始表达式只存储");
        Map<String,Object> source = new HashMap<>(); source.put("maxScore",2); source.put("calculationRule",calculation);
        source.put("nameVector",Arrays.asList(1,2)); source.put("name","名称");
        JsonNode snapshot = IndicatorRuleSourceSnapshot.capture("i",source);
        assertEquals(4,snapshot.size()); assertEquals("i",snapshot.path("indicatorId").asText());
        assertFalse(snapshot.has("nameVector")); assertFalse(snapshot.has("name")); assertTrue(snapshot.get("riskRule").isNull());
        calculation.put("expression","被替换");
        assertEquals("原始表达式只存储",snapshot.path("calculationRule").path("expression").asText());
    }
    @Test void oldSnapshotRemainsNullableAndNewSourceSurvivesJsonRoundTrip() throws Exception {
        assertNull(mapper.readValue("{\"name\":\"历史候选\"}",RetrievalCandidateSnapshot.class).getSourceRule());
        RetrievalCandidateSnapshot snapshot = RetrievalCandidateSnapshot.builder().sourceRule(IndicatorRuleSourceSnapshot.capture("i",Collections.singletonMap("maxScore",1))).build();
        assertEquals(snapshot.getSourceRule(),mapper.readValue(mapper.writeValueAsString(snapshot),RetrievalCandidateSnapshot.class).getSourceRule());
    }
}
