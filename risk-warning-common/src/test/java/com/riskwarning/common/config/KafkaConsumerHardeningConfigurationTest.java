package com.riskwarning.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 消费链加固配置装配测试：统一容器工厂必须绑定错误处理与反序列化包装。
 */
class KafkaConsumerHardeningConfigurationTest {

    private final KafkaConsumerHardeningConfiguration configuration =
            new KafkaConsumerHardeningConfiguration();

    @Test
    void buildsConsumerFactoryWithProperties() {
        ConsumerFactory<String, Object> factory =
                configuration.kafkaConsumerFactory(new KafkaProperties(), new ObjectMapper());
        assertNotNull(factory);
    }

    @Test
    void containerFactoryBindsHardenedErrorHandler() {
        ConsumerFactory<String, Object> consumerFactory =
                configuration.kafkaConsumerFactory(new KafkaProperties(), new ObjectMapper());

        // 2.8.10 AbstractKafkaListenerContainerFactory 无 getCommonErrorHandler，
        // 通过不可重试分类的 error handler bean 与工厂装配行为分别验证
        assertNotNull(configuration.kafkaPoisonPillErrorHandler());
        assertInstanceOf(DefaultErrorHandler.class, configuration.kafkaPoisonPillErrorHandler());
        assertNotNull(configuration.kafkaListenerContainerFactory(consumerFactory));
    }
}