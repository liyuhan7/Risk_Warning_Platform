package com.riskwarning.common.reliability;

import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.utils.KafkaUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AfterCommitKafkaOutboxTest {

    private final KafkaUtils kafkaUtils = mock(KafkaUtils.class);
    private final AssessmentFlowLogger flowLogger = mock(AssessmentFlowLogger.class);
    private final AfterCommitKafkaOutbox outbox =
            new AfterCommitKafkaOutbox(kafkaUtils, flowLogger);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void sendsImmediatelyOutsideTransaction() {
        NotificationMessage message = notification();

        outbox.enqueue(message);

        verify(kafkaUtils).sendMessage(message);
        assertThat(outbox.isDurable()).isFalse();
    }

    @Test
    void holdsMessageUntilCommitInsideTransaction() {
        beginTransactionSynchronization();

        outbox.enqueue(notification());
        verifyNoInteractions(kafkaUtils);

        registeredSynchronization().afterCommit();

        verify(kafkaUtils).sendMessage(any(NotificationMessage.class));
    }

    @Test
    void rollbackNeverPublishesPhantomMessage() {
        beginTransactionSynchronization();

        outbox.enqueue(notification());
        registeredSynchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(kafkaUtils);
    }

    @Test
    void postCommitSendFailureIsLoggedNotPropagated() {
        beginTransactionSynchronization();
        doThrow(new IllegalStateException("broker unavailable"))
                .when(kafkaUtils).sendMessage(any(NotificationMessage.class));

        outbox.enqueue(notification());

        assertThatCode(() -> registeredSynchronization().afterCommit())
                .doesNotThrowAnyException();
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private TransactionSynchronization registeredSynchronization() {
        return TransactionSynchronizationManager.getSynchronizations().get(0);
    }

    private NotificationMessage notification() {
        return new NotificationMessage("msg_1", "timestamp", "trace", 1L, 10L, 20L);
    }
}
