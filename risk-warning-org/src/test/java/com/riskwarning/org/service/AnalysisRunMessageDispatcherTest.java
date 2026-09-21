package com.riskwarning.org.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.utils.KafkaUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalysisRunMessageDispatcherTest {

    private final KafkaUtils kafkaUtils = mock(KafkaUtils.class);
    private final AnalysisRunService runService = mock(AnalysisRunService.class);
    private final KafkaOutbox outbox = mock(KafkaOutbox.class);
    private final AnalysisRunMessageDispatcher durableDispatcher =
            new AnalysisRunMessageDispatcher(kafkaUtils, runService, outbox);
    private final AnalysisRunMessageDispatcher fallbackDispatcher =
            new AnalysisRunMessageDispatcher(kafkaUtils, runService, outbox);
    private final AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
    private final BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
            "message", "timestamp", "trace", 1L, 10L, 20L, "run-1",
            DataSourceTypeEnum.FILE_UPLOAD, Collections.emptyList());

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void rejectsMessageWhoseScopeDoesNotMatchRun() {
        BehaviorProcessingTaskMessage wrongMessage = new BehaviorProcessingTaskMessage(
                "message", "timestamp", "trace", 1L, 10L, 20L, "run-2",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.emptyList());
        assertThrows(IllegalArgumentException.class,
                () -> durableDispatcher.dispatch(scope, wrongMessage));
        assertThrows(IllegalArgumentException.class,
                () -> fallbackDispatcher.dispatch(scope, wrongMessage));
    }

    @Test
    void durableModeEnqueuesMessageInsideTransaction() {
        when(outbox.isDurable()).thenReturn(true);
        beginTransactionSynchronization();

        durableDispatcher.dispatch(scope, message);

        verify(outbox).enqueue(message);
        verifyNoInteractions(kafkaUtils, runService);
    }

    @Test
    void durableModeRejectsEnqueueOutsideTransaction() {
        when(outbox.isDurable()).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> durableDispatcher.dispatch(scope, message));
        verify(outbox, never()).enqueue(any());
        verifyNoInteractions(kafkaUtils, runService);
    }

    @Test
    void durableModeEnqueueFailurePropagatesWithoutFailingRun() {
        when(outbox.isDurable()).thenReturn(true);
        doThrow(new IllegalStateException("insert failed"))
                .when(outbox).enqueue(message);
        beginTransactionSynchronization();

        assertThrows(IllegalStateException.class,
                () -> durableDispatcher.dispatch(scope, message));
        // 入库失败由业务事务回滚，不单独标记运行失败
        verifyNoInteractions(kafkaUtils, runService);
    }

    @Test
    void fallbackModeRejectsRegistrationOutsideTransaction() {
        when(outbox.isDurable()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> fallbackDispatcher.dispatch(scope, message));
        verifyNoInteractions(kafkaUtils, runService);
    }

    @Test
    void fallbackModeSendsOnlyAfterCommit() throws Exception {
        when(outbox.isDurable()).thenReturn(false);
        beginTransactionSynchronization();
        fallbackDispatcher.dispatch(scope, message);
        verifyNoInteractions(kafkaUtils);

        registeredSynchronization().afterCommit();

        verify(kafkaUtils).sendMessageAndWait(message);
        verify(outbox, never()).enqueue(any());
        verifyNoInteractions(runService);
    }

    @Test
    void fallbackModeMarksRunFailedWhenBrokerRejectsMessage() throws Exception {
        when(outbox.isDurable()).thenReturn(false);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(kafkaUtils).sendMessageAndWait(message);
        beginTransactionSynchronization();
        fallbackDispatcher.dispatch(scope, message);

        registeredSynchronization().afterCommit();

        verify(runService).markDispatchFailed(eq(scope), any());
    }

    @Test
    void fallbackModeRollbackDoesNotPublishMessage() {
        when(outbox.isDurable()).thenReturn(false);
        beginTransactionSynchronization();
        fallbackDispatcher.dispatch(scope, message);

        registeredSynchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(kafkaUtils, runService);
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private TransactionSynchronization registeredSynchronization() {
        return TransactionSynchronizationManager.getSynchronizations().get(0);
    }
}
