package com.riskwarning.processing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "p2")
public class P2ProcessingProperties {
    public enum Mode { LEGACY, P2 }
    private Mode mode = Mode.LEGACY;
    private int batchSize = 16;
}
