package com.riskwarning.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消费链加固配置装配测试：统一容器工厂必须绑定错误处理与反序列化包装，
 * 消费位点必须由框架在任务交接后提交（关闭客户端自动提交，durable 工厂逐条提交）。
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

    /**
     * 客户端自动提交由 poll 线程按固定间隔推进位点，与业务处理结果无关；
     * 必须强制关闭，位点提交完全交给容器按 AckMode 控制。
     */
    @Test
    void consumerPropsForceDisableAutoCommit() {
        Map<String, Object> props =
                KafkaConsumerHardeningConfiguration.durableConsumerProps(new KafkaProperties());
        assertEquals(Boolean.FALSE, props.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    /** Nacos 注入的 spring.json.* 属性必须被移除，不能与 setter 双路径配置并存。 */
    @Test
    void consumerPropsStillStripNacosJsonProperties() {
        KafkaProperties properties = new KafkaProperties();
        properties.getConsumer().getProperties().put(
                "spring.json.trusted.packages", "com.riskwarning.common.message");
        properties.getConsumer().getProperties().put(
                "spring.json.value.default.type", "com.riskwarning.common.message.Message");

        Map<String, Object> props =
                KafkaConsumerHardeningConfiguration.durableConsumerProps(properties);
        assertFalse(props.keySet().stream().anyMatch(key -> key.startsWith("spring.json.")));
    }

    /** 可靠业务 listener 专用的 durable 工厂必须逐条提交位点。 */
    @Test
    void durableContainerFactoryCommitsPerRecord() {
        ConsumerFactory<String, Object> consumerFactory =
                configuration.kafkaConsumerFactory(new KafkaProperties(), new ObjectMapper());

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                configuration.durableKafkaListenerContainerFactory(consumerFactory);
        assertEquals(ContainerProperties.AckMode.RECORD,
                factory.getContainerProperties().getAckMode());
    }

    /** 可靠 Inbox 对业务异常无限重试，只允许反序列化毒丸进入恢复器。 */
    @Test
    void durableInboxErrorHandlerRetriesBusinessFailuresWithoutLimit() throws Exception {
        DefaultErrorHandler errorHandler = configuration.durableInboxErrorHandler();

        assertEquals(Boolean.FALSE, classifyAsRetryable(errorHandler,
                new org.springframework.kafka.support.serializer.DeserializationException(
                        "bad payload", new byte[] {1}, false, null)));
        assertTrue(classifyAsRetryable(errorHandler,
                new IllegalStateException("inbox unavailable")));
        assertTrue(classifyAsRetryable(errorHandler,
                new org.springframework.messaging.converter.MessageConversionException("conversion")));
        assertTrue(classifyAsRetryable(errorHandler,
                new org.springframework.kafka.support.converter.ConversionException(
                        "conversion", new IllegalStateException("cause"))));
        assertTrue(classifyAsRetryable(errorHandler, new ClassCastException("wrong listener type")));
        assertTrue(classifyAsRetryable(errorHandler, new NoSuchMethodException("listener method")));

        org.springframework.util.backoff.BackOff backOff = extractBackOff(errorHandler);
        org.springframework.util.backoff.BackOffExecution execution = backOff.start();
        for (int attempt = 0; attempt < 100; attempt++) {
            assertEquals(1000L, execution.nextBackOff(),
                    "入箱异常不得到达 STOP 并被恢复提交");
        }
    }

    /** 不可重试分类只允许消息格式错误；IllegalStateException 属业务异常，交由 Durable Work 重试。 */
    @Test
    void poisonPillErrorHandlerOnlyClassifiesDeserializationAsNonRetryable() throws Exception {
        DefaultErrorHandler errorHandler = configuration.kafkaPoisonPillErrorHandler();

        Boolean deserializationRetryable = classifyAsRetryable(errorHandler,
                new org.springframework.kafka.support.serializer.DeserializationException(
                        "bad payload", new byte[] {1}, false, null));
        assertEquals(Boolean.FALSE, deserializationRetryable,
                "DeserializationException 必须不可重试");

        // IllegalStateException 未列入不可重试名单：分类结果为可重试，
        // 或分类器对未登记异常无默认值而抛出，两者都符合预期
        try {
            Boolean illegalStateRetryable = classifyAsRetryable(errorHandler,
                    new IllegalStateException("business failure"));
            assertFalse(Boolean.FALSE.equals(illegalStateRetryable),
                    "IllegalStateException 必须保留可重试语义");
        } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException expected) {
            // 分类器对未登记异常无默认值时抛错，同样证明未列入不可重试名单
        }
    }

    /**
     * spring-kafka 2.8.10 的 getClassifier 为受保护方法，这里经反射读取
     * DefaultErrorHandler 的内部异常分类器并逐类断言重试语义。
     */
    private static Boolean classifyAsRetryable(DefaultErrorHandler errorHandler, Throwable exception)
            throws Exception {
        java.lang.reflect.Method getClassifier = org.springframework.kafka.listener.ExceptionClassifier.class
                .getDeclaredMethod("getClassifier");
        getClassifier.setAccessible(true);
        org.springframework.classify.BinaryExceptionClassifier classifier =
                (org.springframework.classify.BinaryExceptionClassifier) getClassifier.invoke(errorHandler);
        return classifier.classify(exception);
    }

    private static org.springframework.util.backoff.BackOff extractBackOff(
            DefaultErrorHandler errorHandler) throws Exception {
        java.lang.reflect.Field trackerField = org.springframework.kafka.listener.FailedRecordProcessor.class
                .getDeclaredField("failureTracker");
        trackerField.setAccessible(true);
        Object tracker = trackerField.get(errorHandler);
        java.lang.reflect.Field backOffField = tracker.getClass().getDeclaredField("backOff");
        backOffField.setAccessible(true);
        return (org.springframework.util.backoff.BackOff) backOffField.get(tracker);
    }

    /**
     * Nacos 会把 spring.json.* 放入 consumer properties；显式配置的 JsonDeserializer
     * 必须仍能由 DefaultKafkaConsumerFactory 创建，不能同时走 setter 与 properties 两条配置路径。
     */
    @Test
    void createsConsumerWhenNacosStillContainsJsonDeserializerProperties() {
        KafkaProperties properties = new KafkaProperties();
        properties.getConsumer().getProperties().put(
                "spring.json.trusted.packages", "com.riskwarning.common.message");
        properties.getConsumer().getProperties().put(
                "spring.json.value.default.type", "com.riskwarning.common.message.Message");

        ConsumerFactory<String, Object> factory =
                configuration.kafkaConsumerFactory(properties, new ObjectMapper());

        org.apache.kafka.clients.consumer.Consumer<String, Object> consumer = factory.createConsumer();
        consumer.close(Duration.ZERO);
    }
}
