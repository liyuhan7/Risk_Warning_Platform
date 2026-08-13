package com.riskwarning.knowledge.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Slf4j
@Repository
@RequiredArgsConstructor
public class ElasticsearchRepository {
    
    private final ElasticsearchClient elasticsearchClient;
    
  
    public <T> List<DocumentWithId<T>> searchDocuments(String indexName, int from, int size, 
                                                       String[] sourceFields, Class<T> clazz) {
        try {
            SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                    .index(indexName)
                    .from(from)
                    .size(size);
            
            if (sourceFields != null && sourceFields.length > 0) {
                searchBuilder.source(s -> s.filter(f -> f.includes(Arrays.asList(sourceFields))));
            }
            
            SearchResponse<T> response = elasticsearchClient.search(
                    searchBuilder.build(), 
                    clazz
            );
            
            List<DocumentWithId<T>> documents = new ArrayList<>();
            for (Hit<T> hit : response.hits().hits()) {
                DocumentWithId<T> doc = new DocumentWithId<>();
                doc.setId(hit.id());
                doc.setSource(hit.source());
                documents.add(doc);
            }
            
            return documents;
        } catch (Exception e) {
            throw new RuntimeException("查询ES文档失败: " + e.getMessage(), e);
        }
    }
    
  
    public long getDocumentCount(String indexName) {
        try {
            CountRequest countRequest = new CountRequest.Builder()
                    .index(indexName)
                    .build();
            
            CountResponse response = elasticsearchClient.count(countRequest);
            return response.count();
        } catch (Exception e) {
            throw new RuntimeException("获取文档总数失败: " + e.getMessage(), e);
        }
    }

    public long getDocumentCountWithField(String indexName, String fieldName) {
        try {
            CountRequest countRequest = new CountRequest.Builder()
                    .index(indexName)
                    .query(q -> q.exists(e -> e.field(fieldName)))
                    .build();

            return elasticsearchClient.count(countRequest).count();
        } catch (Exception e) {
            throw new RuntimeException("获取ES字段覆盖数量失败: " + e.getMessage(), e);
        }
    }

    public <T> List<DocumentWithId<T>> searchDocumentsMissingField(
            String indexName,
            String textFieldName,
            String missingFieldName,
            int size,
            Class<T> clazz) {
        try {
            SearchRequest searchRequest = new SearchRequest.Builder()
                    .index(indexName)
                    .size(size)
                    .source(s -> s.filter(f -> f.includes(textFieldName)))
                    .query(q -> q.bool(b -> b
                            .must(m -> m.exists(e -> e.field(textFieldName)))
                            .mustNot(m -> m.exists(e -> e.field(missingFieldName)))))
                    .build();

            SearchResponse<T> response = elasticsearchClient.search(searchRequest, clazz);
            List<DocumentWithId<T>> documents = new ArrayList<>();
            for (Hit<T> hit : response.hits().hits()) {
                DocumentWithId<T> document = new DocumentWithId<>();
                document.setId(hit.id());
                document.setSource(hit.source());
                documents.add(document);
            }
            return documents;
        } catch (Exception e) {
            throw new RuntimeException("查询缺失字段的ES文档失败: " + e.getMessage(), e);
        }
    }
    

    public BulkUpdateResult batchUpdateVectorFields(String indexName,
                                                    List<VectorUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return new BulkUpdateResult(0, 0);
        }
        
        try {
            List<BulkOperation> bulkOperations = new ArrayList<>();
            
            for (VectorUpdate update : updates) {
                // ES的dense_vector字段需要List<Float>格式，而不是float[]
                List<Float> validVector = new ArrayList<>();
                for (Float value : update.getVector()) {
                    if (value == null) {
                        validVector.add(0.0f);
                    } else {
                        validVector.add(value);
                    }
                }
                
                String scriptSource = String.format(
                    "if (ctx._source.containsKey('%s')) { " +
                    "  ctx._source.remove('%s'); " +
                    "} " +
                    "ctx._source.%s = params.vector",
                    update.getVectorFieldName(),
                    update.getVectorFieldName(),
                    update.getVectorFieldName()
                );
                Map<String, JsonData> scriptParams = new HashMap<>();
                scriptParams.put("vector", JsonData.of(validVector));
                
                BulkOperation operation = BulkOperation.of(op -> op
                        .update(u -> u
                                .index(indexName)
                                .id(update.getDocumentId())
                                .action(a -> a
                                        .script(s -> s
                                                .inline(i -> i
                                                        .source(scriptSource)
                                                        .params(scriptParams)
                                                )
                                        )
                                )
                        )
                );
                
                bulkOperations.add(operation);
            }
            
            BulkRequest bulkRequest = new BulkRequest.Builder()
                    .operations(bulkOperations)
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)  // 立即刷新
                    .build();
            
            BulkResponse bulkResponse = elasticsearchClient.bulk(bulkRequest);
            
            // 统计成功和失败的数量
            long successCount = 0;
            long failedCount = 0;
            List<String> errorMessages = new ArrayList<>();
            
            for (BulkResponseItem item : bulkResponse.items()) {
                if (item.error() != null) {
                    failedCount++;
                    String errorMsg = String.format("id=%s, error=%s", item.id(), item.error().reason());
                    errorMessages.add(errorMsg);
                    log.error("更新失败: {}", errorMsg);
                } else {
                    successCount++;
                }
            }

            return new BulkUpdateResult(successCount, failedCount);
        } catch (Exception e) {
            log.error("批量更新向量字段失败: index={}", indexName, e);
            throw new RuntimeException("批量更新向量字段失败: " + e.getMessage(), e);
        }
    }
    
    public void updateVectorField(String indexName, String documentId, 
                                 String vectorFieldName, List<Float> vector) {
        try {
            // 将List<Float>转换为float[]数组
            float[] vectorArray = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                vectorArray[i] = vector.get(i);
            }
            
            Map<String, JsonData> updateMap = new HashMap<>();
            updateMap.put(vectorFieldName, JsonData.of(vectorArray));
            
            UpdateRequest<Map<String, JsonData>, ?> updateRequest = 
                    new UpdateRequest.Builder<Map<String, JsonData>, Object>()
                            .index(indexName)
                            .id(documentId)
                            .doc(updateMap)
                            .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)
                            .build();
            
            UpdateResponse<?> response = elasticsearchClient.update(updateRequest, Map.class);
            
        } catch (Exception e) {
            throw new RuntimeException("更新向量字段失败: " + e.getMessage(), e);
        }
    }
    

    @lombok.Data
    public static class DocumentWithId<T> {
        private String id;
        private T source;
    }
    
    /**
     * 向量更新数据
     */
    @lombok.Data
    public static class VectorUpdate {
        private String documentId;
        private String vectorFieldName;
        private List<Float> vector;
    }

    public static class BulkUpdateResult {
        private final long successCount;
        private final long failedCount;

        public BulkUpdateResult(long successCount, long failedCount) {
            this.successCount = successCount;
            this.failedCount = failedCount;
        }

        public long getSuccessCount() {
            return successCount;
        }

        public long getFailedCount() {
            return failedCount;
        }
    }
}

