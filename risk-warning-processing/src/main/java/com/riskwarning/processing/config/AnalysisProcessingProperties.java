package com.riskwarning.processing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "analysis.processing")
public class AnalysisProcessingProperties {
    private int batchSize = 16;
}
