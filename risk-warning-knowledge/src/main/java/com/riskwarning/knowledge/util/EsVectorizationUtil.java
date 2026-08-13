package com.riskwarning.knowledge.util;

import com.riskwarning.knowledge.config.VectorConfig;
import com.riskwarning.knowledge.repository.ElasticsearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Component
@RequiredArgsConstructor
public class EsVectorizationUtil {
    
    private final ElasticsearchRepository esRepository;
    private final VectorizationUtil vectorizationUtil;
    private final VectorConfig vectorConfig;
    
    // 批量处理大小
    private static final int BATCH_SIZE = 50;
    
    public VectorizationResult vectorizeIndicators() {
        return vectorizeIndex(
                "t_indicator",
                "name",
                "name_vector",
                doc -> {
                    Map<String, Object> source = doc.getSource();
                    if (source != null) {
                        Object nameObj = source.get("name");
                        return nameObj != null ? nameObj.toString() : null;
                    }
                    return null;
                }
        );
    }
    
    
    public VectorizationResult vectorizeRegulations() {
        return vectorizeIndex(
                "t_regulation",
                "full_text",
                "full_text_vector",
                doc -> {
                    Map<String, Object> source = doc.getSource();
                    if (source != null) {
                        Object fullTextObj = source.get("full_text");
                        return fullTextObj != null ? fullTextObj.toString() : null;
                    }
                    return null;
                }
        );
    }
    
    public VectorizationResult vectorizeBehaviors() {
        return vectorizeIndex(
                "t_behavior",
                "description",
                "description_vector",
                doc -> {
                    Map<String, Object> source = doc.getSource();
                    if (source != null) {
                        Object descObj = source.get("description");
                        return descObj != null ? descObj.toString() : null;
                    }
                    return null;
                }
        );
    }
    
    private VectorizationResult vectorizeIndex(String indexName, 
                                               String textFieldName,
                                               String vectorFieldName,
                                               TextExtractor textExtractor) {
        log.info("开始向量化索引: {}, 字段: {}", indexName, textFieldName);
        
        VectorizationResult result = new VectorizationResult();
        result.setIndexName(indexName);
        result.setTextFieldName(textFieldName);
        result.setVectorFieldName(vectorFieldName);
        result.setStartTime(System.currentTimeMillis());
        
        try {
            // 1. 获取文档总数
            long totalCount = esRepository.getDocumentCount(indexName);
            result.setTotalCount(totalCount);

            
            if (totalCount == 0) {
                result.setSuccessCount(0);
                result.setFailedCount(0);
                result.setEndTime(System.currentTimeMillis());
                return result;
            }
            
            long coveredCount = esRepository.getDocumentCountWithField(indexName, vectorFieldName);
            result.setSuccessCount(coveredCount);
            result.setFailedCount(totalCount - coveredCount);

            // 每次只取仍缺少向量的首批文档。已更新文档会退出结果集，避免
            // from/size 分页期间 refresh 导致文档重排、重复处理和漏处理。
            while (coveredCount < totalCount) {
                double progress = totalCount > 0 ? (double) coveredCount / totalCount * 100 : 0;
                log.info("处理进度: {}/{} ({}%)",
                        coveredCount, totalCount, String.format("%.2f", progress));

                @SuppressWarnings("unchecked")
                List<ElasticsearchRepository.DocumentWithId<Map<String, Object>>> documents = 
                        (List<ElasticsearchRepository.DocumentWithId<Map<String, Object>>>)
                        (List<?>) esRepository.searchDocumentsMissingField(
                                indexName, textFieldName, vectorFieldName, BATCH_SIZE, Map.class);
                
                if (documents.isEmpty()) {
                    break;
                }
                
                // 2.2 提取文本并过滤空值
                List<DocumentText> documentTexts = new ArrayList<>();
                for (ElasticsearchRepository.DocumentWithId<Map<String, Object>> doc : documents) {
                    String text = textExtractor.extract(doc);
                    if (text != null && !text.trim().isEmpty()) {
                        DocumentText docText = new DocumentText();
                        docText.setDocumentId(doc.getId());
                        docText.setText(text);
                        documentTexts.add(docText);
                    }
                }
                
                if (documentTexts.isEmpty()) {
                    result.setError("存在缺少可向量化文本的文档");
                    break;
                }
                
                // 2.3 批量向量化
                List<String> texts = documentTexts.stream()
                        .map(DocumentText::getText)
                        .collect(Collectors.toList());
                
                List<float[]> vectors;
                try {
                    vectors = vectorizationUtil.batchVectorize(texts);
                } catch (Exception e) {
                    result.setError("批量向量化失败: " + e.getMessage());
                    break;
                }

                if (vectors.size() != documentTexts.size()) {
                    result.setError("向量化返回数量与请求文本数量不一致");
                    break;
                }
                
                // 2.4 准备更新数据
                List<ElasticsearchRepository.VectorUpdate> updates = new ArrayList<>();
                for (int i = 0; i < documentTexts.size(); i++) {
                    DocumentText docText = documentTexts.get(i);
                    float[] vector = vectors.get(i);
                    
                    // 验证向量有效性
                    if (vector == null || vector.length == 0) {
                        result.setFailedCount(result.getFailedCount() + 1);
                        continue;
                    }
                    
                    // 验证向量维度
                    int expectedDimension = vectorConfig.getDimension();
                    if (vector.length != expectedDimension) {
                        log.warn("文档 {} 的向量维度不正确: 期望={}, 实际={}", 
                                docText.getDocumentId(), expectedDimension, vector.length);
                        result.setFailedCount(result.getFailedCount() + 1);
                        continue;
                    }
                    
                    // 转换为List<Float>
                    List<Float> vectorList = new ArrayList<>();
                    for (float f : vector) {
                        vectorList.add(f);
                    }
                    
                    ElasticsearchRepository.VectorUpdate update = 
                            new ElasticsearchRepository.VectorUpdate();
                    update.setDocumentId(docText.getDocumentId());
                    update.setVectorFieldName(vectorFieldName);
                    update.setVector(vectorList);
                    updates.add(update);
                }
                
                if (updates.isEmpty()) {
                    result.setError("当前批次未生成有效向量");
                    break;
                }
                
                // 2.5 批量更新ES
                try {
                    ElasticsearchRepository.BulkUpdateResult bulkResult =
                            esRepository.batchUpdateVectorFields(indexName, updates);
                    if (bulkResult.getFailedCount() > 0) {
                        result.setError("ES批量更新失败: " + bulkResult.getFailedCount() + " 条");
                        break;
                    }
                } catch (Exception e) {
                    result.setError("ES批量更新失败: " + e.getMessage());
                    break;
                }

                long newCoveredCount = esRepository.getDocumentCountWithField(indexName, vectorFieldName);
                if (newCoveredCount <= coveredCount) {
                    result.setError("向量字段覆盖数量没有增长，已停止处理");
                    break;
                }
                coveredCount = newCoveredCount;
                
                // 避免请求过快，稍微延迟
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            coveredCount = esRepository.getDocumentCountWithField(indexName, vectorFieldName);
            result.setSuccessCount(coveredCount);
            result.setFailedCount(totalCount - coveredCount);
            if (result.getFailedCount() > 0 && result.getError() == null) {
                result.setError("仍有 " + result.getFailedCount() + " 条文档缺少向量");
            }

            result.setEndTime(System.currentTimeMillis());
            long duration = result.getEndTime() - result.getStartTime();
            
        } catch (Exception e) {
            result.setError(e.getMessage());
            result.setEndTime(System.currentTimeMillis());
        }
        
        return result;
    }
    
   
    @FunctionalInterface
    private interface TextExtractor {
        String extract(ElasticsearchRepository.DocumentWithId<Map<String, Object>> document);
    }
    
  
    @lombok.Data
    private static class DocumentText {
        private String documentId;
        private String text;
    }
    
    @lombok.Data
    public static class VectorizationResult {
        private String indexName;
        private String textFieldName;
        private String vectorFieldName;
        private long totalCount;
        private long successCount;
        private long failedCount;
        private long startTime;
        private long endTime;
        private String error;
        
        public long getDuration() {
            return endTime > startTime ? endTime - startTime : 0;
        }
        
        public double getSuccessRate() {
            return totalCount > 0 ? (double) successCount / totalCount * 100 : 0;
        }
    }
}

