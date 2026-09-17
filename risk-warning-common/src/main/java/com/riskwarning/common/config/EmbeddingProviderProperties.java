package com.riskwarning.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Embedding 独立配置；默认关闭以避免 common 扫描影响无关服务。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingProviderProperties {
    private boolean enabled;
    private String endpoint = "https://api.siliconflow.cn/v1/embeddings";
    private String model = "BAAI/bge-m3";
    /** 仅通过 EMBEDDING_API_KEY 注入，不记录到日志。 */
    private String apiKey;
    private int dimension = 1024;
    private int batchSize = 16;
    private int timeoutSeconds = 120;
    private int maxAttempts = 3;
    private long initialBackoffMillis = 500;
}
