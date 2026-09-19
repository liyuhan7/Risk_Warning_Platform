package com.riskwarning.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 新检索配置，与旧 BERT/Milvus 配置独立。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "retrieval")
public class RetrievalProperties {
    public enum DenseScoreMode { ES_NORMALIZED, RAW_COSINE }
    private boolean enabled;
    private String indicatorIndex = "t_indicator";
    private String regulationIndex = "t_regulation";
    private String embeddingVersion = "bge-m3@2026-09-16-retrieval-v1";
    private int indicatorTopK = 10;
    private int regulationTopK = 10;
    private int numCandidates = 100;
    private int fusionWindow = 100;
    private double denseWeight = 0.5;
    private double bm25Weight = 0.5;
    private DenseScoreMode denseScoreMode = DenseScoreMode.ES_NORMALIZED;
    /** COSINE_SIMILARITY 与 HYBRID 分属不同评分空间，必须成对配置才启用质量门禁。 */
    private Double indicatorMinScore;
    private Double regulationMinScore;

    public boolean isQualityGateEnabled() {
        return indicatorMinScore != null && regulationMinScore != null;
    }
}
