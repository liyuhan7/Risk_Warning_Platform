package com.riskwarning.common.reliability;

import com.riskwarning.common.utils.KafkaUtils;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * R1-03 真实 Kafka 隔离环境。
 * 每个测试使用随机 topic/group；故障注入场景通过测试专用 producer
 * 超时配置模拟 broker 不可用，不修改生产发送语义。
 */
public final class ReliabilityKafkaEnv {

    private final String bootstrapServers;
    private AdminClient adminClient;

    private ReliabilityKafkaEnv(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public static ReliabilityKafkaEnv start() {
        return new ReliabilityKafkaEnv(
                envOr("R1_03_KAFKA_BOOTSTRAP", "127.0.0.1:9092"));
    }

    /** 供生产 KafkaOutboxHandler 使用的 KafkaUtils，默认发送超时。 */
    public KafkaUtils kafkaUtils() {
        return kafkaUtils(producerProps(30_000L));
    }

    /** 故障注入用：发送在指定毫秒内无法确认即失败。 */
    public KafkaUtils unreachableKafkaUtils(long deliveryTimeoutMillis) {
        Map<String, Object> props = producerProps(30_000L);
        props.put("bootstrap.servers", deadEndpoint());
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) deliveryTimeoutMillis);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) Math.max(deliveryTimeoutMillis, 1_000L));
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                (int) Math.min(deliveryTimeoutMillis, 10_000L));
        return kafkaUtils(props);
    }

    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps(30_000L)));
    }

    /** String 消费者用于验证真实投递内容，不依赖业务反序列化。 */
    public KafkaConsumer<String, String> stringConsumer(String group) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return new KafkaConsumer<>(props);
    }

    /** 创建随机后缀 topic，避免复用生产 group/topic。 */
    public void createTopic(String topic, int partitions) {
        ensureAdmin();
        adminClient.createTopics(Collections.singletonList(
                new NewTopic(topic, partitions, (short) 1))).values();
    }

    public void deleteTopic(String topic) {
        ensureAdmin();
        try {
            adminClient.deleteTopics(Collections.singletonList(topic)).all().get();
        } catch (Exception exception) {
            // topic 清理失败不影响测试结果，随机命名不会复用
        }
    }

    public String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public String getBootstrapServers() { return bootstrapServers; }

    public void stop() {
        if (adminClient != null) {
            adminClient.close();
        }
    }

    private KafkaUtils kafkaUtils(Map<String, Object> producerProps) {
        DefaultKafkaProducerFactory<String, Object> producerFactory =
                new DefaultKafkaProducerFactory<>(producerProps);
        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        KafkaUtils kafkaUtils = new KafkaUtils();
        ReflectionTestUtils.setField(kafkaUtils, "kafkaTemplate", kafkaTemplate);
        return kafkaUtils;
    }

    private Map<String, Object> producerProps(long sendTimeoutMillis) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) sendTimeoutMillis);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) sendTimeoutMillis);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    private void ensureAdmin() {
        if (adminClient == null) {
            Properties properties = new Properties();
            properties.put("bootstrap.servers", bootstrapServers);
            adminClient = AdminClient.create(properties);
        }
    }

    private String deadEndpoint() {
        return "127.0.0.1:19092";
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
