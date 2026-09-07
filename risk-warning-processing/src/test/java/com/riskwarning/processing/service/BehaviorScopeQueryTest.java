package com.riskwarning.processing.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorScopeQueryTest {

    @Test
    void buildsExactProjectAssessmentAndRunTerms() {
        Query query = BehaviorProcessingService.buildBehaviorScopeQuery(10L, 20L, "run-1");

        assertTrue(query.isBool());
        List<Query> terms = query.bool().must();
        assertEquals(3, terms.size());
        assertEquals("projectId", terms.get(0).term().field());
        assertEquals(10L, terms.get(0).term().value().longValue());
        assertEquals("assessmentId", terms.get(1).term().field());
        assertEquals(20L, terms.get(1).term().value().longValue());
        assertEquals("analysisRunId", terms.get(2).term().field());
        assertEquals("run-1", terms.get(2).term().value().stringValue());
    }

    @Test
    void rejectsIncompleteScopeInsteadOfFallingBackToProjectQuery() {
        assertThrows(IllegalArgumentException.class,
                () -> BehaviorProcessingService.buildBehaviorScopeQuery(10L, 20L, " "));
    }
}
