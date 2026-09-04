package com.riskwarning.knowledge.repository;

import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.response.SearchResultsWrapper;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;

@Slf4j
@Repository
@RequiredArgsConstructor
public class MilvusRepository {
    
    private final MilvusServiceClient milvusClient;
    
    
    public void createCollectionIfNotExists(String collectionName, int dimension) {
        
        HasCollectionParam hasCollectionParam = HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
        
        R<Boolean> hasCollection = milvusClient.hasCollection(hasCollectionParam);
        if (hasCollection.getData() != null && hasCollection.getData()) {
            return;
        }
        
        // 定义字段
        List<FieldType> fields = new ArrayList<>();
        
        // ID字段
        fields.add(FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build());
        
        // 向量字段
        fields.add(FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build());
        
        // ES ID字段（关联到Elasticsearch）
        fields.add(FieldType.newBuilder()
                .withName("es_id")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build());
        
        // 名称字段
        fields.add(FieldType.newBuilder()
                .withName("name")
                .withDataType(DataType.VarChar)
                .withMaxLength(256)
                .build());
        
        // 维度字段
        fields.add(FieldType.newBuilder()
                .withName("dimension")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build());
        
        // 合规领域字段（P0-11 决议 1.5：industry 语义改名，与 ES complianceDomain 同窗口）
        fields.add(FieldType.newBuilder()
                .withName("compliance_domain")
                .withDataType(DataType.VarChar)
                .withMaxLength(256)
                .build());
        
        // 地域字段
        fields.add(FieldType.newBuilder()
                .withName("region")
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .build());
        
        // 创建集合
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("向量集合: " + collectionName)
                .withFieldTypes(fields)
                .withShardsNum(2)
                .build();
        
        R<RpcStatus> response = milvusClient.createCollection(createParam);
        if (response.getStatus() == R.Status.Success.getCode()) {
        } else {
            throw new RuntimeException("集合创建失败: " + response.getMessage());
        }
    }
    
 
    public void createIndexIfNotExists(String collectionName) {
        try {
            // 创建索引
            String extraParams = "{\"nlist\":1024}";
            
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("vector")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam(extraParams)
                    .build();
            
            R<RpcStatus> response = milvusClient.createIndex(indexParam);
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("already exists") || errorMsg.contains("already exist"))) {
                log.debug("索引已存在（异常）: {}", collectionName);
            } else {
                log.warn("索引创建异常: {}, 错误: {}", collectionName, e.getMessage());
            }
        }
    }
    

    public void loadCollectionIfNotLoaded(String collectionName) {
        try {
            // 尝试加载集合
            LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();
            
            R<RpcStatus> response = milvusClient.loadCollection(loadParam);
            if (response.getStatus() == R.Status.Success.getCode()) {
                log.info("集合加载成功: {}", collectionName);
            } else {
                // 如果加载失败，可能是没有索引，先创建索引
                String errorMsg = response.getMessage();
                if (errorMsg != null && errorMsg.contains("index not found")) {
                    createIndexIfNotExists(collectionName);
                    // 等待索引创建完成
                    Thread.sleep(2000);
                    // 再次尝试加载
                    R<RpcStatus> retryResponse = milvusClient.loadCollection(loadParam);
                }
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg != null && errorMsg.contains("index not found")) {
                // 如果是因为没有索引，创建索引后重试
                log.info("集合加载异常（缺少索引），创建索引后重试: {}", collectionName);
                try {
                    createIndexIfNotExists(collectionName);
                    Thread.sleep(2000);
                    LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build();
                    R<RpcStatus> retryResponse = milvusClient.loadCollection(loadParam);
                } catch (Exception ex) {
                    log.error("集合加载最终失败: {}", collectionName, ex);
                }
            }
        }
    }
    
    

    public void insertVectors(String collectionName, List<String> ids, 
                              List<List<Float>> vectors,
                              List<String> esIds, List<String> names,
                              List<String> dimensions, List<String> industries, 
                              List<String> regions) {
        
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids不能为空");
        }
        
        int rowCount = ids.size();
        
        // 准备数据（按行组织，每行是一个JSONObject）
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            JSONObject row = new JSONObject();
            row.put("id", ids.get(i));
            row.put("vector", vectors.get(i));
            row.put("es_id", esIds != null && i < esIds.size() ? esIds.get(i) : "");
            row.put("name", names != null && i < names.size() ? names.get(i) : "");
            row.put("dimension", dimensions != null && i < dimensions.size() ? dimensions.get(i) : "");
            row.put("compliance_domain", industries != null && i < industries.size() ? industries.get(i) : "");
            row.put("region", regions != null && i < regions.size() ? regions.get(i) : "");
            rows.add(row);
        }
        
        // 构建InsertParam - 使用withRows方法
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withRows(rows)
                .build();
        
        R<MutationResult> response = milvusClient.insert(insertParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("向量插入失败: " + response.getMessage());
        }
    }
    
    public List<SearchResultsWrapper.IDScore> search(String collectionName, 
                                                      List<Float> queryVector,
                                                      int topK,
                                                      String expr) {
        List<List<Float>> queryVectors = Collections.singletonList(queryVector);
        
        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName("vector")
                .withVectors(queryVectors)
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withOutFields(Arrays.asList("id", "es_id", "name", "dimension", "compliance_domain", "region"))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG);

        if (expr != null && !expr.trim().isEmpty()) {
            searchBuilder.withExpr(expr);
        }

        SearchParam searchParam = searchBuilder.build();

        R<SearchResults> response = milvusClient.search(searchParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("向量搜索失败: " + response.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        return wrapper.getIDScore(0);  // 返回第一个查询的结果
    }
    

    public SearchResultsWrapper searchWithWrapper(String collectionName, 
                                                   List<Float> queryVector,
                                                   int topK,
                                                   String expr) {
        // 搜索前确保集合已加载（如果未加载，先创建索引再加载）
        try {
            loadCollectionIfNotLoaded(collectionName);
        } catch (Exception e) {
            log.warn("搜索前加载集合失败，继续尝试搜索: {}", e.getMessage());
        }
        
        List<List<Float>> queryVectors = Collections.singletonList(queryVector);
        
        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName("vector")
                .withVectors(queryVectors)
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withOutFields(Arrays.asList("id", "es_id", "name", "dimension", "compliance_domain", "region"))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG);

        if (expr != null && !expr.trim().isEmpty()) {
            searchBuilder.withExpr(expr);
        }

        SearchParam searchParam = searchBuilder.build();

        R<SearchResults> response = milvusClient.search(searchParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            String errorMsg = response.getMessage();
            // 如果是因为未加载，尝试创建索引并加载
            if (errorMsg != null && errorMsg.contains("collection not loaded")) {
                try {
                    createIndexIfNotExists(collectionName);
                    Thread.sleep(1000);
                    loadCollectionIfNotLoaded(collectionName);
                    Thread.sleep(500);
                    // 重试搜索
                    response = milvusClient.search(searchParam);
                    if (response.getStatus() == R.Status.Success.getCode()) {
                        return new SearchResultsWrapper(response.getData().getResults());
                    }
                } catch (Exception e) {
                    log.error("重试搜索失败: {}", collectionName, e);
                }
            }
            throw new RuntimeException("向量搜索失败: " + errorMsg);
        }
        
        return new SearchResultsWrapper(response.getData().getResults());
    }
    
    /**
     * 检查集合是否存在
     */
    public boolean hasCollection(String collectionName) {
        HasCollectionParam hasCollectionParam = HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();
        
        R<Boolean> response = milvusClient.hasCollection(hasCollectionParam);
        return response.getData() != null && response.getData();
    }
}

