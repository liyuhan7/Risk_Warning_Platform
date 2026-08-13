package com.riskwarning.knowledge.controller;

import com.riskwarning.knowledge.util.EsVectorizationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/vectorization")
@RequiredArgsConstructor
public class EsVectorizationController {
    
    private final EsVectorizationUtil esVectorizationUtil;
    
    /**
     * 向量化t_indicator索引的name字段
     */
    @PostMapping("/indicators")
    public Map<String, Object> vectorizeIndicators() {
        EsVectorizationUtil.VectorizationResult result = 
                esVectorizationUtil.vectorizeIndicators();
        return buildResponse(result);
    }
    
    /**
     * 向量化t_regulation索引的full_text字段
     */
    @PostMapping("/regulations")
    public Map<String, Object> vectorizeRegulations() {
        EsVectorizationUtil.VectorizationResult result = 
                esVectorizationUtil.vectorizeRegulations();
        return buildResponse(result);
    }
    
    /**
     * 向量化t_behavior索引的description字段
     */
    @PostMapping("/behaviors")
    public Map<String, Object> vectorizeBehaviors() {
        EsVectorizationUtil.VectorizationResult result = 
                esVectorizationUtil.vectorizeBehaviors();
        return buildResponse(result);
    }
    
    /**
     * 批量向量化所有索引
     */
    @PostMapping("/all")
    public Map<String, Object> vectorizeAll() {
        Map<String, Object> response = new HashMap<>();
        
        // 向量化indicators
        EsVectorizationUtil.VectorizationResult indicatorsResult = 
                esVectorizationUtil.vectorizeIndicators();
        response.put("indicators", buildResponse(indicatorsResult));
        
        // 向量化regulations
        EsVectorizationUtil.VectorizationResult regulationsResult = 
                esVectorizationUtil.vectorizeRegulations();
        response.put("regulations", buildResponse(regulationsResult));
        
        // 向量化behaviors
        EsVectorizationUtil.VectorizationResult behaviorsResult = 
                esVectorizationUtil.vectorizeBehaviors();
        response.put("behaviors", buildResponse(behaviorsResult));
        
        // 汇总统计
        long totalCount = indicatorsResult.getTotalCount() + 
                         regulationsResult.getTotalCount() + 
                         behaviorsResult.getTotalCount();
        long totalSuccess = indicatorsResult.getSuccessCount() + 
                           regulationsResult.getSuccessCount() + 
                           behaviorsResult.getSuccessCount();
        long totalFailed = indicatorsResult.getFailedCount() + 
                          regulationsResult.getFailedCount() + 
                          behaviorsResult.getFailedCount();
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalCount", totalCount);
        summary.put("totalSuccess", totalSuccess);
        summary.put("totalFailed", totalFailed);
        summary.put("totalDuration", 
                indicatorsResult.getDuration() + 
                regulationsResult.getDuration() + 
                behaviorsResult.getDuration());
        
        response.put("summary", summary);
        response.put("code", 200);
        response.put("message", "批量向量化完成");
        
        return response;
    }
    
   
    private Map<String, Object> buildResponse(EsVectorizationUtil.VectorizationResult result) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", result.getError() == null ? 200 : 500);
        response.put("message", result.getError() == null ? "向量化完成" : "向量化失败: " + result.getError());
        
        Map<String, Object> data = new HashMap<>();
        data.put("indexName", result.getIndexName());
        data.put("textFieldName", result.getTextFieldName());
        data.put("vectorFieldName", result.getVectorFieldName());
        data.put("totalCount", result.getTotalCount());
        data.put("successCount", result.getSuccessCount());
        data.put("failedCount", result.getFailedCount());
        data.put("successRate", String.format("%.2f%%", result.getSuccessRate()));
        data.put("duration", result.getDuration() + "ms");
        
        response.put("data", data);
        return response;
    }
    
}

