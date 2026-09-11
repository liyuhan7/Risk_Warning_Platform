package com.riskwarning.processing.repository;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.riskwarning.common.po.behavior.Behavior;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchBehaviorDocumentRepositoryTest {

    @Test
    void buildsIndexOperationWithBehaviorStableId() {
        Behavior behavior = Behavior.builder().id("stable-id").description("事实").build();

        BulkRequest request = ElasticsearchBehaviorDocumentRepository.buildRequest(
                Collections.singletonList(behavior));

        assertEquals("t_behavior", request.operations().get(0).index().index());
        assertEquals("stable-id", request.operations().get(0).index().id());
    }

    @Test
    void rejectsBehaviorWithoutStableId() {
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchBehaviorDocumentRepository.buildRequest(
                        Collections.singletonList(Behavior.builder().description("事实").build())));
    }

    @Test
    void buildsDeleteRequestByRunAndSourceDocument() {
        DeleteByQueryRequest request = ElasticsearchBehaviorDocumentRepository
                .buildDeleteRequest("run-1", 101L);

        assertEquals(Collections.singletonList("t_behavior"), request.index());
        List<Query> must = request.query().bool().must();
        assertEquals(2, must.size());
        for (Query query : must) {
            assertTrue(query.isTerm());
            String field = query.term().field();
            if ("analysisRunId".equals(field)) {
                assertEquals("run-1", query.term().value().stringValue());
            } else if ("sourceDocumentId".equals(field)) {
                assertEquals(101L, query.term().value().longValue());
            } else {
                throw new AssertionError("delete 查询包含意外字段: " + field);
            }
        }
    }

    @Test
    void rejectsDeleteRequestWithoutValidScope() {
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchBehaviorDocumentRepository.buildDeleteRequest("", 101L));
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchBehaviorDocumentRepository.buildDeleteRequest("run-1", null));
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchBehaviorDocumentRepository.buildDeleteRequest("run-1", 0L));
    }

    @Test
    void buildsScopeSearchRequestWithExactThreeTerms() {
        SearchRequest request = ElasticsearchBehaviorDocumentRepository
                .buildScopeSearchRequest(10L, 20L, "run-1");

        assertEquals(Collections.singletonList("t_behavior"), request.index());
        assertTrue(request.trackTotalHits().enabled());
        List<Query> must = request.query().bool().must();
        assertEquals(3, must.size());
        for (Query query : must) {
            assertTrue(query.isTerm());
            String field = query.term().field();
            if ("projectId".equals(field)) {
                assertEquals(10L, query.term().value().longValue());
            } else if ("assessmentId".equals(field)) {
                assertEquals(20L, query.term().value().longValue());
            } else if ("analysisRunId".equals(field)) {
                assertEquals("run-1", query.term().value().stringValue());
            } else {
                throw new AssertionError("作用域查询包含意外字段: " + field);
            }
        }
    }

    @Test
    void rejectsScopeSearchWithoutCompleteScope() {
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchBehaviorDocumentRepository.buildScopeSearchRequest(10L, 20L, " "));
        assertThrows(IllegalArgumentException.class,
                () -> ElasticsearchBehaviorDocumentRepository.buildScopeSearchRequest(null, 20L, "run-1"));
    }
}
