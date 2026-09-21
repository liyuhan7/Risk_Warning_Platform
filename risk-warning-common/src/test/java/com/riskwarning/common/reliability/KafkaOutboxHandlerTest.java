package com.riskwarning.common.reliability;

import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.utils.KafkaUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KafkaOutboxHandlerTest {

    private final KafkaUtils kafkaUtils = mock(KafkaUtils.class);
    private final KafkaOutboxCodec codec = new KafkaOutboxCodec();
    private final KafkaOutboxHandler handler = new KafkaOutboxHandler(kafkaUtils, codec);

    @Test
    void exposesOutboxKindAndExhaustionLimit() {
        assertThat(handler.kind()).isEqualTo("KAFKA_OUTBOX");
        assertThat(handler.maxAttempts()).isEqualTo(20);
    }

    @Test
    void decodesPayloadAndSendsWithBrokerAckWait() throws Exception {
        NotificationMessage message = new NotificationMessage(
                "msg_1", "timestamp", "trace", 1L, 10L, 20L);
        DurableWorkContext context = context(3);

        handler.execute(context, codec.encode(message));

        verify(kafkaUtils).sendMessageAndWait(message, KafkaOutboxHandler.SEND_TIMEOUT_MILLIS);
    }

    @Test
    void undecodablePayloadFailsBeforeAnySend() {
        assertThatThrownBy(() -> handler.execute(context(1), "{not-json"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(kafkaUtils);
    }

    @Test
    void sendFailurePropagatesToDurableWorkRetry() throws Exception {
        NotificationMessage message = new NotificationMessage(
                "msg_1", "timestamp", "trace", 1L, 10L, 20L);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(kafkaUtils).sendMessageAndWait(any(NotificationMessage.class),
                        eq(KafkaOutboxHandler.SEND_TIMEOUT_MILLIS));

        assertThatThrownBy(() -> handler.execute(context(1), codec.encode(message)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broker unavailable");
    }

    @Test
    void exhaustionLogsStableAlertMarkerWithoutThrowing() {
        NotificationMessage message = new NotificationMessage(
                "msg_1", "timestamp", "trace", 1L, 10L, 20L);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> handler.onExhausted(context(20), codec.encode(message),
                        new IllegalStateException("broker unavailable")));
    }

    @Test
    void exhaustionWithBrokenPayloadStillLogsAndDoesNotThrow() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> handler.onExhausted(context(20), "{not-json",
                        new IllegalStateException("broker unavailable")));
    }

    private DurableWorkContext context(int attempts) {
        DurableWork work = new DurableWork("work-1", "risk-warning-processing", KafkaOutboxHandler.KIND,
                "msg_1", "payload", attempts, "worker-1", "lease-1", LocalDateTime.now().plusMinutes(10));
        return new DurableWorkContext(work);
    }
}

