package com.riskwarning.processing.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.riskwarning.common.po.behavior.Behavior;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 使用 Behavior 稳定 ID 作为 Elasticsearch 文档 _id。 */
@Slf4j
@Repository
public class ElasticsearchBehaviorDocumentRepository implements BehaviorDocumentRepository {

    private static final String BEHAVIOR_INDEX = "t_behavior";

    /** 单次作用域查询的返回上限；命中超过时记录告警，不静默截断。 */
    private static final int MAX_SCOPE_RESULT_SIZE = 10000;

    private final ElasticsearchClient elasticsearchClient;

    public ElasticsearchBehaviorDocumentRepository(ElasticsearchClient elasticsearchClient) {
        this.elasticsearchClient = elasticsearchClient;
    }

    @Override
    public void writeAll(List<Behavior> behaviors) {
        if (behaviors == null) {
            throw new IllegalArgumentException("Behavior 列表不能为空");
        }
        if (behaviors.isEmpty()) {
            return;
        }
        try {
            BulkResponse response = elasticsearchClient.bulk(buildRequest(behaviors));
            if (response.errors()) {
                throw new IllegalStateException("Structured Behavior 批量写入 Elasticsearch 失败");
            }
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException("Structured Behavior 写入 Elasticsearch 失败", exception);
        }
    }

    @Override
    public List<Behavior> findByScope(Long projectId, Long assessmentId, String analysisRunId) {
        try {
            SearchResponse<Behavior> response = elasticsearchClient.search(
                    buildScopeSearchRequest(projectId, assessmentId, analysisRunId), Behavior.class);
            if (response == null || response.hits() == null || response.hits().hits() == null) {
                return Collections.emptyList();
            }
            if (response.hits().total() != null && response.hits().total().value() > MAX_SCOPE_RESULT_SIZE) {
                log.warn("[Behavior Query] projectId={}, assessmentId={}, analysisRunId={} 命中 {} 条，"
                                + "单次查询上限为 {}，结果可能被截断",
                        projectId, assessmentId, analysisRunId,
                        response.hits().total().value(), MAX_SCOPE_RESULT_SIZE);
            }
            List<Behavior> behaviors = new ArrayList<>();
            for (Hit<Behavior> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    behaviors.add(hit.source());
                }
            }
            return behaviors;
        } catch (IOException exception) {
            throw new IllegalStateException("查询 Structured Behavior 失败", exception);
        }
    }

    @Override
    public void deleteByAnalysisRunIdAndSourceDocumentId(String analysisRunId,
                                                         Long sourceDocumentId) {
        if (analysisRunId == null || analysisRunId.trim().isEmpty()
                || sourceDocumentId == null || sourceDocumentId <= 0) {
            throw new IllegalArgumentException("清理范围需要有效的 analysisRunId 与 sourceDocumentId");
        }
        try {
            DeleteByQueryResponse response = elasticsearchClient.deleteByQuery(
                    buildDeleteRequest(analysisRunId, sourceDocumentId));
            if (response.failures() != null && !response.failures().isEmpty()) {
                throw new IllegalStateException("清理旧 Behavior 存在失败项");
            }
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException("清理旧 Behavior 失败", exception);
        }
    }

    static BulkRequest buildRequest(List<Behavior> behaviors) {
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (Behavior behavior : behaviors) {
            if (behavior == null || behavior.getId() == null || behavior.getId().trim().isEmpty()) {
                throw new IllegalArgumentException("写入 ES 的 Behavior 缺少稳定 ID");
            }
            builder.operations(operation -> operation.index(index -> index
                    .index(BEHAVIOR_INDEX)
                    .id(behavior.getId())
                    .document(behavior)));
        }
        return builder.build();
    }

    static SearchRequest buildScopeSearchRequest(Long projectId, Long assessmentId, String analysisRunId) {
        Query query = BehaviorScopeQuery.build(projectId, assessmentId, analysisRunId);
        return SearchRequest.of(builder -> builder
                .index(BEHAVIOR_INDEX)
                .size(MAX_SCOPE_RESULT_SIZE)
                .trackTotalHits(total -> total.enabled(true))
                .query(query));
    }

    static DeleteByQueryRequest buildDeleteRequest(String analysisRunId, Long sourceDocumentId) {
        if (analysisRunId == null || analysisRunId.trim().isEmpty()
                || sourceDocumentId == null || sourceDocumentId <= 0) {
            throw new IllegalArgumentException("清理范围需要有效的 analysisRunId 与 sourceDocumentId");
        }
        return DeleteByQueryRequest.of(builder -> builder
                .index(BEHAVIOR_INDEX)
                .query(query -> query.bool(bool -> bool
                        .must(term -> term.term(field -> field
                                .field("analysisRunId").value(analysisRunId)))
                        .must(term -> term.term(field -> field
                                .field("sourceDocumentId").value(sourceDocumentId))))));
    }
}
