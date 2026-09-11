package com.riskwarning.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 消费链加固行为测试：缺 __TypeId__ 头不静默、毒丸不卡分区、正常消息可回读。
 * 不依赖 Kafka Broker，直接验证 deserializer 层。
 */
class KafkaDeserializerHardeningTest {

    private static final String TOPIC = "behavior_processing_tasks";
    private static final String TYPE_ID_HEADER = "__TypeId__";

    private JsonDeserializer<Object> hardenedJsonDeserializer() {
        JsonDeserializer<Object> deserializer = new JsonDeserializer<>(new ObjectMapper());
        deserializer.setUseTypeHeaders(true);
        deserializer.addTrustedPackages("com.riskwarning.**");
        return deserializer;
    }

    private ErrorHandlingDeserializer<Object> errorHandling(JsonDeserializer<Object> json) {
        return new ErrorHandlingDeserializer<>(json);
    }

    @Test
    void missingTypeHeaderFailsExplicitlyInsteadOfSilentlyDropping() {
        JsonDeserializer<Object> json = hardenedJsonDeserializer();
        Headers headers = new RecordHeaders();

        // 缺 __TypeId__ 头且未配置默认类型：必须显式抛异常（否则会静默走错分支或被丢弃）
        assertThrows(Exception.class,
                () -> json.deserialize(TOPIC, headers, "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingTypeHeaderIsCapturedAsRecoverableErrorByErrorHandlingDeserializer() {
        ErrorHandlingDeserializer<Object> errorHandling = errorHandling(hardenedJsonDeserializer());
        Headers headers = new RecordHeaders();

        Object value = errorHandling.deserialize(TOPIC, headers,
                "{\"projectId\":10}".getBytes(StandardCharsets.UTF_8));

        // 缺类型头的消息被显式捕获：值为 null 且在 headers 中可识别，进入错误处理而非静默丢失
        assertNull(value);
        assertNotNull(headers.lastHeader(ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER));
    }

    @Test
    void poisonPillPayloadIsCapturedAsRecoverableErrorWithoutBlocking() {
        ErrorHandlingDeserializer<Object> errorHandling = errorHandling(hardenedJsonDeserializer());
        Headers headers = new RecordHeaders();
        headers.add(new RecordHeader(TYPE_ID_HEADER,
                BehaviorProcessingTaskMessage.class.getName().getBytes(StandardCharsets.UTF_8)));

        byte[] poisonPill = new byte[]{(byte) 0x00, (byte) 0xFF, (byte) 0x01, (byte) 0x02, 0x40, 0x23};

        Object value = errorHandling.deserialize(TOPIC, headers, poisonPill);

        // 毒丸 payload 不会向 listener 抛异常卡死分区，而是转为可跳过错误
        assertNull(value);
        assertNotNull(headers.lastHeader(ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER));
    }

    @Test
    void validMessageWithTypeHeaderRoundTrips() {
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                "msg-1", "2026-09-11T10:00:00", "trace-1", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.singletonList("upload.docx"));

        Serializer<Object> serializer = new JsonSerializer<>();
        Headers headers = new RecordHeaders();
        byte[] payload = serializer.serialize(TOPIC, headers, message);
        assertNotNull(headers.lastHeader(TYPE_ID_HEADER));

        ErrorHandlingDeserializer<Object> errorHandling = errorHandling(hardenedJsonDeserializer());
        Object value = errorHandling.deserialize(TOPIC, headers, payload);

        assertEquals(BehaviorProcessingTaskMessage.class, value.getClass());
        assertEquals("run-1", ((BehaviorProcessingTaskMessage) value).getAnalysisRunId());
        assertEquals(Long.valueOf(10L), ((BehaviorProcessingTaskMessage) value).getProjectId());
        // 正常消息不得产生反序列化错误头
        assertNull(headers.lastHeader(ErrorHandlingDeserializer.VALUE_DESERIALIZER_EXCEPTION_HEADER),
                "正常消息不得产生反序列化错误头");
    }
}