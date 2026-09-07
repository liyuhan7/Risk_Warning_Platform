package com.riskwarning.org.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.utils.KafkaUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalysisRunMessageDispatcherTest {

    private final KafkaUtils kafkaUtils = mock(KafkaUtils.class);
    private final AnalysisRunService runService = mock(AnalysisRunService.class);
    private final AnalysisRunMessageDispatcher dispatcher =
            new AnalysisRunMessageDispatcher(kafkaUtils, runService);
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
    void rejectsRegistrationOutsideTransaction() {
        assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatchAfterCommit(scope, message));
    }

    @Test
    void rejectsMessageWhoseScopeDoesNotMatchRun() {
        BehaviorProcessingTaskMessage wrongMessage = new BehaviorProcessingTaskMessage(
                "message", "timestamp", "trace", 1L, 10L, 20L, "run-2",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.emptyList());
        assertThrows(IllegalArgumentException.class,
                () -> dispatcher.dispatchAfterCommit(scope, wrongMessage));
    }

    @Test
    void sendsOnlyAfterCommit() throws Exception {
        beginTransactionSynchronization();
        dispatcher.dispatchAfterCommit(scope, message);
        verifyNoInteractions(kafkaUtils);

        registeredSynchronization().afterCommit();

        verify(kafkaUtils).sendMessageAndWait(message);
        verifyNoInteractions(runService);
    }

    @Test
    void marksRunFailedWhenBrokerRejectsMessage() throws Exception {
        doThrow(new IllegalStateException("broker unavailable"))
                .when(kafkaUtils).sendMessageAndWait(message);
        beginTransactionSynchronization();
        dispatcher.dispatchAfterCommit(scope, message);

        registeredSynchronization().afterCommit();

        verify(runService).markDispatchFailed(eq(scope), any());
    }

    @Test
    void rollbackDoesNotPublishMessage() {
        beginTransactionSynchronization();
        dispatcher.dispatchAfterCommit(scope, message);

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
