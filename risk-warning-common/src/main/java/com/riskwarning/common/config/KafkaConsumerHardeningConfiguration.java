package com.riskwarning.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
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
 * 3. 消费位点只由框架在 listener 返回后提交——关闭客户端自动提交，
 *    可靠业务 listener 使用 durable 工厂逐条提交，处理中崩溃不丢失任务所有权。
 */
@Configuration
public class KafkaConsumerHardeningConfiguration {

    @Bean
    public ConsumerFactory<String, Object> kafkaConsumerFactory(
            KafkaProperties properties, ObjectMapper objectMapper) {
        Map<String, Object> consumerProps = durableConsumerProps(properties);
        JsonDeserializer<Object> jsonDeserializer = new JsonDeserializer<>(objectMapper);
        jsonDeserializer.setUseTypeHeaders(true);
        jsonDeserializer.addTrustedPackages("com.riskwarning.**");
        ErrorHandlingDeserializer<Object> errorHandlingDeserializer =
                new ErrorHandlingDeserializer<>(jsonDeserializer);
        return new DefaultKafkaConsumerFactory<>(consumerProps,
                new StringDeserializer(), errorHandlingDeserializer);
    }

    /**
     * 构建消费端属性：统一移除 spring.json.*，并强制关闭客户端自动提交。
     * 自动提交由 poll 线程按固定间隔推进位点，与业务处理完成与否无关；
     * 关闭后位点提交完全由容器按 AckMode 控制，任务交接成功前位点不前进。
     */
    static Map<String, Object> durableConsumerProps(KafkaProperties properties) {
        Map<String, Object> consumerProps = properties.buildConsumerProperties();
        // JsonDeserializer 在实际实例上通过 setter 完整配置。Spring Kafka 禁止同一实例
        // 再读取 spring.json.* 属性；而 Nacos 的 value.default.type 还会让缺类型头消息
        // 降级为 Message，违背"缺类型头显式失败"的边界，因此必须统一移除这组属性。
        consumerProps.keySet().removeIf(key -> key.startsWith("spring.json."));
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.FALSE);
        return consumerProps;
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

    /**
     * 可靠业务 listener 专用的 Durable 容器工厂。
     * AckMode.RECORD：每条消息在 listener 正常返回后才提交该条位点，
     * 服务崩溃时未完成交接的任务不会被跳过，重启后重新投递。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> durableKafkaListenerContainerFactory(
            ConsumerFactory<String, Object> kafkaConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kafkaConsumerFactory);
        factory.setCommonErrorHandler(durableInboxErrorHandler());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }

    @Bean
    public DefaultErrorHandler kafkaPoisonPillErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                poisonPillRecoverer(), new FixedBackOff(0L, 0L));
        // 兼容 listener 保持既有恢复策略；可靠 Inbox 使用独立错误处理器。
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        return errorHandler;
    }

    /**
     * 可靠 Inbox 仅允许反序列化毒丸被跳过；入箱异常必须持续重投，
     * 错误处理器不得正常返回并推进消费位点。
     */
    @Bean
    public DefaultErrorHandler durableInboxErrorHandler() {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                poisonPillRecoverer(),
                new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        // 清除框架默认的不可重试名单，避免类型转换、参数解析等 listener 异常被误当毒丸跳过。
        java.util.Map<Class<? extends Throwable>, Boolean> classifications = new java.util.HashMap<>();
        classifications.put(DeserializationException.class, Boolean.FALSE);
        errorHandler.setClassifications(classifications, true);
        return errorHandler;
    }

    private ConsumerRecordRecoverer poisonPillRecoverer() {
        return (record, exception) -> {
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
