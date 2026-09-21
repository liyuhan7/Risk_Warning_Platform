package com.riskwarning.common.reliability;

import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalKafkaOutboxTest {

    private final DurableWorkStore store = mock(DurableWorkStore.class);
    private final KafkaOutboxCodec codec = new KafkaOutboxCodec();
    private final AssessmentFlowLogger flowLogger = mock(AssessmentFlowLogger.class);
    private final TransactionalKafkaOutbox outbox =
            new TransactionalKafkaOutbox(store, codec, flowLogger);

    @Test
    void writesEnvelopeIntoDurableWorkUnderOutboxKind() throws Exception {
        NotificationMessage message = notification("msg_1");

        outbox.enqueue(message);

        verify(store).enqueue(eq(KafkaOutboxHandler.KIND), eq("msg_1"), any());
        // 捕获到的载荷必须能还原为同一消息
        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).enqueue(eq(KafkaOutboxHandler.KIND), eq("msg_1"), payload.capture());
        assertThat(codec.decode(payload.getValue()).getMessageId()).isEqualTo("msg_1");
        assertThat(outbox.isDurable()).isTrue();
    }

    @Test
    void duplicateInsertConflictIsToleratedAsIdempotentDedup() {
        NotificationMessage message = notification("msg_1");
        when(store.enqueue(eq(KafkaOutboxHandler.KIND), eq("msg_1"), any())).thenReturn(false);

        assertThatCode(() -> outbox.enqueue(message)).doesNotThrowAnyException();
    }

    @Test
    void invalidMessageIsRejectedBeforeTouchingStore() {
        assertThatThrownBy(() -> outbox.enqueue(new NotificationMessage()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, org.mockito.Mockito.never())
                .enqueue(any(), any(), any());
    }

    private NotificationMessage notification(String messageId) {
        return new NotificationMessage(messageId, "timestamp", "trace", 1L, 10L, 20L);
    }
}
