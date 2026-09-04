package com.riskwarning.knowledge.service;

import com.riskwarning.knowledge.config.VectorConfig;
import com.riskwarning.knowledge.dto.VectorSearchResult;
import com.riskwarning.knowledge.repository.MilvusRepository;
import com.riskwarning.knowledge.util.VectorizationUtil;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class VectorSearchService {
    
    private final VectorizationUtil vectorizationUtil;
    private final MilvusRepository milvusRepository;
    private final VectorConfig vectorConfig;
    
    // 集合名称常量
    private static final String INDICATOR_COLLECTION = "indicator_vectors";
    private static final String REGULATION_COLLECTION = "regulation_vectors";
    
 
    public List<VectorSearchResult> searchSimilarIndicators(String queryText, int topK, 
                                                             String industry, String region, String dimension,
                                                             Double similarityThreshold) {
        return searchSimilar(INDICATOR_COLLECTION, queryText, topK, industry, region, dimension, similarityThreshold);
    }
    
  
    public List<VectorSearchResult> searchSimilarRegulations(String queryText, int topK,
                                                               String industry, String region, String dimension,
                                                               Double similarityThreshold) {
        return searchSimilar(REGULATION_COLLECTION, queryText, topK, industry, region, dimension, similarityThreshold);
    }
    
    
    private List<VectorSearchResult> searchSimilar(String collectionName, 
                                                    String queryText, int topK,
                                                    String industry, String region, String dimension,
                                                    Double similarityThreshold) {
        try {
            // 1. 检查集合是否存在
            if (!milvusRepository.hasCollection(collectionName)) {
                return new ArrayList<>();
            }
            
            // 2. 将查询文本向量化
            float[] queryVector = vectorizationUtil.vectorize(queryText);
            
            // 转换为List<Float>
            List<Float> queryVectorList = new ArrayList<>();
            for (float f : queryVector) {
                queryVectorList.add(f);
            }
            
            // 3. 构建过滤表达式
            String expr = buildFilterExpression(industry, region, dimension);
            
            // 4. 在Milvus中搜索
            SearchResultsWrapper wrapper = milvusRepository.searchWithWrapper(
                    collectionName, queryVectorList, topK, expr
            );
            
            // 5. 转换为结果DTO
            List<VectorSearchResult> results = convertToSearchResults(wrapper);
            
            // 6. 应用相似度阈值过滤
            double threshold = similarityThreshold != null ? similarityThreshold : vectorConfig.getSimilarityThreshold();
            if (threshold > 0) {
                results = results.stream()
                        .filter(result -> result.getScore() != null && result.getScore() >= threshold)
                        .collect(Collectors.toList());
            }
            
            return results;
            
        } catch (Exception e) {
            throw new RuntimeException("语义搜索失败: " + e.getMessage(), e);
        }
    }
    
    public List<VectorSearchResult> matchByCategory(String collectionName,
                                                     String dimension, String industry, String region) {
        try {
            // 检查集合是否存在
            if (!milvusRepository.hasCollection(collectionName)) {
                return new ArrayList<>();
            }
            
            // 构建过滤表达式（不需要向量，直接用分类字段过滤）
            String expr = buildFilterExpression(industry, region, dimension);
            
            if (expr == null || expr.trim().isEmpty()) {
                return new ArrayList<>();
            }
            
            // 使用一个零向量进行搜索（因为分类匹配不关心相似度，只需要过滤）
            List<Float> zeroVector = new ArrayList<>();
            for (int i = 0; i < vectorConfig.getDimension(); i++) {
                zeroVector.add(0.0f);
            }
            
            // 设置较大的topK以获取所有匹配项（最多1000条）
            SearchResultsWrapper wrapper = milvusRepository.searchWithWrapper(
                    collectionName, zeroVector, 1000, expr
            );
            
            return convertToSearchResults(wrapper);
            
        } catch (Exception e) {
            throw new RuntimeException("分类匹配失败: " + e.getMessage(), e);
        }
    }
    

    private String buildFilterExpression(String industry, String region, String dimension) {
        List<String> conditions = new ArrayList<>();
        
        // 过滤字段已随 P0-11 决议 1.5 改名：industry -> compliance_domain
        if (industry != null && !industry.trim().isEmpty()) {
            conditions.add(String.format("compliance_domain == \"%s\"", industry.trim()));
        }
        
        if (region != null && !region.trim().isEmpty()) {
            conditions.add(String.format("region == \"%s\"", region.trim()));
        }
        
        if (dimension != null && !dimension.trim().isEmpty()) {
            conditions.add(String.format("dimension == \"%s\"", dimension.trim()));
        }
        
        return conditions.isEmpty() ? null : String.join(" && ", conditions);
    }
    

    private List<VectorSearchResult> convertToSearchResults(SearchResultsWrapper wrapper) {
        List<VectorSearchResult> results = new ArrayList<>();
        
        try {
            // 获取第一个查询的结果（因为我们只查询一次）
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
            
            // 获取字段数据
            List<?> esIdFieldData = null;
            List<?> nameFieldData = null;
            List<?> dimensionFieldData = null;
            List<?> industryFieldData = null;
            List<?> regionFieldData = null;
            
            try {
                if (wrapper.getFieldWrapper("es_id") != null) {
                    esIdFieldData = wrapper.getFieldWrapper("es_id").getFieldData();
                }
                if (wrapper.getFieldWrapper("name") != null) {
                    nameFieldData = wrapper.getFieldWrapper("name").getFieldData();
                }
                if (wrapper.getFieldWrapper("dimension") != null) {
                    dimensionFieldData = wrapper.getFieldWrapper("dimension").getFieldData();
                }
                if (wrapper.getFieldWrapper("compliance_domain") != null) {
                    industryFieldData = wrapper.getFieldWrapper("compliance_domain").getFieldData();
                }
                if (wrapper.getFieldWrapper("region") != null) {
                    regionFieldData = wrapper.getFieldWrapper("region").getFieldData();
                }
            } catch (Exception e) {
                log.warn("获取字段数据失败: {}", e.getMessage());
            }
            
            // 遍历每个搜索结果
            for (int i = 0; i < idScores.size(); i++) {
                SearchResultsWrapper.IDScore idScore = idScores.get(i);
                VectorSearchResult result = new VectorSearchResult();
                
                // 提取ID和分数
                result.setId(idScore.getStrID());
                result.setScore(idScore.getScore());
                
                // 提取outFields中的字段值
                try {
                    if (esIdFieldData != null && i < esIdFieldData.size()) {
                        Object esId = esIdFieldData.get(i);
                        result.setEsId(esId != null ? String.valueOf(esId) : "");
                    }
                    
                    if (nameFieldData != null && i < nameFieldData.size()) {
                        Object name = nameFieldData.get(i);
                        result.setName(name != null ? String.valueOf(name) : "");
                    }
                    
                    if (dimensionFieldData != null && i < dimensionFieldData.size()) {
                        Object dimension = dimensionFieldData.get(i);
                        result.setDimension(dimension != null ? String.valueOf(dimension) : "");
                    }
                    
                    if (industryFieldData != null && i < industryFieldData.size()) {
                        Object industry = industryFieldData.get(i);
                        result.setIndustry(industry != null ? String.valueOf(industry) : "");
                    }
                    
                    if (regionFieldData != null && i < regionFieldData.size()) {
                        Object region = regionFieldData.get(i);
                        result.setRegion(region != null ? String.valueOf(region) : "");
                    }
                } catch (Exception e) {
                }
                
                results.add(result);
            }
        } catch (Exception e) {
            log.error("转换搜索结果失败", e);
        }
        
        return results;
    }
}

