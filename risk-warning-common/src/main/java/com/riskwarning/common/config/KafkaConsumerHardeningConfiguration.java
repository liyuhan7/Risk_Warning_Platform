package com.riskwarning.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Kafka 消费链加固：统一覆盖三个服务（processing/report/notification）的默认监听容器。
 *
 * 目标：
 * 1. 毒丸消息（payload 无法反序列化）不再卡死分区——由 ErrorHandlingDeserializer
 *    捕获为可识别错误，错误处理器固定不重试并跳过提交 offset。
 * 2. 缺少 __TypeId__ 头的消息不再静默丢失——JsonDeserializer 依赖类型头，
 *    缺失时显式失败并记录 ERROR（含 topic/partition/offset）。
 */
@Configuration
public class KafkaConsumerHardeningConfiguration {

    @Bean
    public ConsumerFactory<String, Object> kafkaConsumerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        Map<String, Object> consumerProps = properties.buildConsumerProperties();
        JsonDeserializer<Object> jsonDeserializer = new JsonDeserializer<>(objectMapper);
        jsonDeserializer.setUseTypeHeaders(true);
        jsonDeserializer.addTrustedPackages("com.riskwarning.**");
        ErrorHandlingDeserializer<Object> errorHandlingDeserializer =
                new ErrorHandlingDeserializer<>(jsonDeserializer);
        return new DefaultKafkaConsumerFactory<>(consumerProps,
                new StringDeserializer(), errorHandlingDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> kafkaConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kafkaConsumerFactory);
        factory.setCommonErrorHandler(kafkaPoisonPillErrorHandler());
        return factory;
    }

    @Bean
    public DefaultErrorHandler kafkaPoisonPillErrorHandler() {
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            Throwable cause = exception;
            DeserializationException deserialization = null;
            if (exception instanceof DeserializationException) {
                deserialization = (DeserializationException) exception;
                if (deserialization.getCause() != null) {
                    cause = deserialization.getCause();
                }
            }
            String payload = deserialization != null && deserialization.getData() != null
                    ? preview(deserialization.getData()) : "<不可用>";
            String reason = cause.getMessage() != null ? cause.getMessage()
                    : cause.getClass().getSimpleName();
            org.slf4j.LoggerFactory.getLogger(KafkaConsumerHardeningConfiguration.class)
                    .error("[Kafka消费加固] 跳过不可消费消息 topic={}, partition={}, offset={}, "
                                    + "原因={}, 载荷摘要={}",
                            record.topic(), record.partition(), record.offset(), reason, payload);
        };
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(0L, 0L));
        // 反序列化失败（毒丸/缺类型头）明确为不可重试：直接恢复跳过，避免无限 seek 卡死分区
        errorHandler.addNotRetryableExceptions(DeserializationException.class,
                IllegalStateException.class);
        return errorHandler;
    }

    private static String preview(byte[] data) {
        if (data.length == 0) {
            return "<空>";
        }
        int length = Math.min(data.length, 200);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) {
            int value = data[index] & 0xFF;
            if (value >= 32 && value <= 126) {
                builder.append((char) value);
            } else {
                builder.append(String.format("\\x%02X", value));
            }
        }
        if (data.length > length) {
            builder.append("...(共").append(data.length).append("字节)");
        }
        return builder.toString();
    }
}