package com.riskwarning.knowledge.controller;

import com.riskwarning.knowledge.dto.VectorSearchRequest;
import com.riskwarning.knowledge.dto.VectorSearchResult;
import com.riskwarning.knowledge.service.VectorSearchService;
import com.riskwarning.knowledge.service.VectorStorageService;
import com.riskwarning.knowledge.util.VectorizationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量化功能测试Controller
 * 用于测试向量化功能
 */
@Slf4j
@RestController
@RequestMapping("/test/vectorization")
@RequiredArgsConstructor
public class VectorizationTestController {
    
    private final VectorizationUtil vectorizationUtil;
    private final VectorStorageService vectorStorageService;
    private final VectorSearchService vectorSearchService;
    
    @GetMapping("/single")
    public Map<String, Object> testSingleVectorize(@RequestParam String text) {
        try {
            
            float[] vector = vectorizationUtil.vectorize(text);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("text", text);
            result.put("dimension", vector.length);
            result.put("vector", vector);
            result.put("message", "向量化成功");
        
            return result;
            
        } catch (Exception e) {
            log.error("向量化失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
    
    
    @PostMapping("/batch")
    public Map<String, Object> testBatchVectorize(@RequestBody Map<String, List<String>> request) {
        try {
            List<String> texts = request.get("texts");
            if (texts == null || texts.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "texts字段不能为空");
                return result;
            }
            
            
            List<float[]> vectors = vectorizationUtil.batchVectorize(texts);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("textCount", texts.size());
            result.put("vectorCount", vectors.size());
            result.put("dimension", vectors.isEmpty() ? 0 : vectors.get(0).length);
            result.put("vectors", vectors);
            result.put("message", "批量向量化成功");
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
    
  
    @GetMapping("/dimension")
    public Map<String, Object> testDimension() {
        Map<String, Object> result = new HashMap<>();
        result.put("dimension", vectorizationUtil.getDimension());
        result.put("message", "获取向量维度成功");
        return result;
    }
    
   
    @PostMapping("/store")
    public Map<String, Object> testStoreVector(@RequestBody Map<String, String> request) {
        try {
            String collectionName = request.getOrDefault("collectionName", "test_vectors");
            String id = request.get("id");
            String text = request.get("text");
            String esId = request.get("esId");
            String name = request.get("name");
            String dimension = request.get("dimension");
            String industry = request.get("industry");
            String region = request.get("region");
            
            if (id == null || text == null) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "id和text字段不能为空");
                return result;
            }
            
            vectorStorageService.storeTextVector(
                    collectionName, id, text, esId, name, dimension, industry, region
            );
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "向量存储成功");
            result.put("collectionName", collectionName);
            result.put("id", id);
            return result;
            
        } catch (Exception e) {
            log.error("向量存储失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
    
   
    @PostMapping("/batch-store")
    public Map<String, Object> testBatchStoreVectors(@RequestBody Map<String, Object> request) {
        try {
            String collectionName = (String) request.getOrDefault("collectionName", "test_vectors");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> dataList = (List<Map<String, String>>) request.get("data");
            
            if (dataList == null || dataList.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "data字段不能为空");
                return result;
            }
            
            List<VectorStorageService.VectorData> vectorDataList = new java.util.ArrayList<>();
            for (Map<String, String> data : dataList) {
                VectorStorageService.VectorData vectorData = new VectorStorageService.VectorData();
                vectorData.setId(data.get("id"));
                vectorData.setText(data.get("text"));
                vectorData.setEsId(data.get("esId"));
                vectorData.setName(data.get("name"));
                vectorData.setDimension(data.get("dimension"));
                vectorData.setIndustry(data.get("industry"));
                vectorData.setRegion(data.get("region"));
                vectorDataList.add(vectorData);
            }
            
            vectorStorageService.batchStoreTextVectors(collectionName, vectorDataList);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "批量向量存储成功");
            result.put("collectionName", collectionName);
            result.put("count", vectorDataList.size());
            return result;
            
        } catch (Exception e) {
            log.error("批量向量存储失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
    
    @PostMapping("/search/indicators")
    public Map<String, Object> searchIndicators(@RequestBody VectorSearchRequest request) {
        try {
            if (request.getQueryText() == null || request.getQueryText().trim().isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "queryText字段不能为空");
                return result;
            }
            
            int topK = request.getTopK() != null ? request.getTopK() : 10;
            List<VectorSearchResult> results = vectorSearchService.searchSimilarIndicators(
                    request.getQueryText(),
                    topK,
                    request.getIndustry(),
                    request.getRegion(),
                    request.getDimension(),
                    request.getSimilarityThreshold()
            );
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "搜索成功");
            result.put("count", results.size());
            result.put("results", results);
            return result;
            
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
    
    @PostMapping("/search/regulations")
    public Map<String, Object> searchRegulations(@RequestBody VectorSearchRequest request) {
        try {
            if (request.getQueryText() == null || request.getQueryText().trim().isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "queryText字段不能为空");
                return result;
            }
            
            int topK = request.getTopK() != null ? request.getTopK() : 10;
            List<VectorSearchResult> results = vectorSearchService.searchSimilarRegulations(
                    request.getQueryText(),
                    topK,
                    request.getIndustry(),
                    request.getRegion(),
                    request.getDimension(),
                    request.getSimilarityThreshold()
            );
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "搜索成功");
            result.put("count", results.size());
            result.put("results", results);
            return result;
            
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
    

    @PostMapping("/match/category")
    public Map<String, Object> matchByCategory(@RequestBody Map<String, String> request) {
        try {
            String collectionType = request.get("collectionType"); // "indicator" 或 "regulation"
            String dimension = request.get("dimension");
            String industry = request.get("industry");
            String region = request.get("region");
            
            if (collectionType == null || collectionType.trim().isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "collectionType字段不能为空（indicator 或 regulation）");
                return result;
            }
            
            // 判断集合名称
            String collectionName;
            if ("indicator".equalsIgnoreCase(collectionType)) {
                collectionName = "indicator_vectors";
            } else if ("regulation".equalsIgnoreCase(collectionType)) {
                collectionName = "regulation_vectors";
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "collectionType必须是 indicator 或 regulation");
                return result;
            }
            
            // 至少需要一个过滤条件
            if ((dimension == null || dimension.trim().isEmpty()) &&
                (industry == null || industry.trim().isEmpty()) &&
                (region == null || region.trim().isEmpty())) {
                Map<String, Object> result = new HashMap<>();
                result.put("success", false);
                result.put("error", "至少需要提供一个过滤条件（dimension、industry或region）");
                return result;
            }
            
            List<VectorSearchResult> results = vectorSearchService.matchByCategory(
                    collectionName, dimension, industry, region
            );
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "分类匹配成功");
            result.put("collectionType", collectionType);
            result.put("count", results.size());
            result.put("results", results);
            return result;
            
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }
    }
    
}

