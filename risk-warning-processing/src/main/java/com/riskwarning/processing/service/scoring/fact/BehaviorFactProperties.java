package com.riskwarning.processing.service.scoring.fact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 按行为抽取的请求预算与进程内并发上限。 */
@Data
@Component
@ConfigurationProperties(prefix = "analysis.rules")
public class BehaviorFactProperties {
    public enum Mode { INDICATOR, BEHAVIOR }
    private Mode factMode = Mode.INDICATOR;
    private String factCatalog = "classpath:rules/labor-fact-bindings-v1.json";
    private int factMaxQuestions = 16;
    private int factMaxInputCodePoints = 12000;
    private int factConcurrency = 3;

    public void validate() {
        if (factMode == null || factMaxQuestions < 1 || factMaxInputCodePoints < 1 || factConcurrency < 1)
            throw new IllegalArgumentException("事实抽取模式、请求预算和并发上限必须有效");
    }
}
