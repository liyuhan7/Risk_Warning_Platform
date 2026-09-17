package com.riskwarning.processing.config;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** 检索调用不在 Feign 层重试，避免叠加 Embedding Provider 的三次尝试。 */
public class RetrievalFeignConfiguration {
    @Bean
    public Retryer retrievalRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
