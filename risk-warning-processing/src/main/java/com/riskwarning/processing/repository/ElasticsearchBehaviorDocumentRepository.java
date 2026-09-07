package com.riskwarning.processing.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import com.riskwarning.common.po.behavior.Behavior;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 使用 Behavior 稳定 ID 作为 Elasticsearch 文档 _id。 */
@Repository
public class ElasticsearchBehaviorDocumentRepository implements BehaviorDocumentRepository {

    private static final String BEHAVIOR_INDEX = "t_behavior";

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
