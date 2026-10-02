package com.riskwarning.processing.task;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.processing.service.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IndicatorCalculationWorkHandlerTest {
    private final ProcessingMessageCodec codec = mock(ProcessingMessageCodec.class);
    private final AnalysisRunFailureService failures = mock(AnalysisRunFailureService.class);
    private final AnalysisRunModeResolver identity = mock(AnalysisRunModeResolver.class);
    private final ComplianceAnalysisOrchestrator analysis = mock(ComplianceAnalysisOrchestrator.class);
    private final IndicatorCalculationWorkHandler handler = new IndicatorCalculationWorkHandler(codec, failures, identity, analysis);
    private final AnalysisScope scope = new AnalysisScope(10L, 20L, "run");
    private final DurableWorkContext context = new DurableWorkContext(new com.riskwarning.common.reliability.DurableWork("work", "processing", IndicatorCalculationWorkHandler.KIND, "message", "{}", 1, "worker", "lease", LocalDateTime.now().plusMinutes(15)));

    private void message() {
        when(codec.decodeIndicator("payload")).thenReturn(new IndicatorCalculationTaskMessage(
                "message", "time", "trace", 1L, 10L, 20L, "run"));
        when(identity.resolve(scope)).thenReturn(AnalysisExecutionMode.REASONING);
    }

    @Test void validMessageAlwaysEntersAnalysis() {
        message();
        handler.execute(context, "payload");
        verify(identity).resolve(scope);
        verify(analysis).process(any(IndicatorCalculationTaskMessage.class), eq(scope));
        verifyNoInteractions(failures);
    }

    @Test void unsupportedIdentityDoesNotEnterAnalysis() {
        message();
        when(identity.resolve(scope)).thenThrow(new IllegalStateException("invalid identity"));
        assertThrows(IllegalStateException.class, () -> handler.execute(context, "payload"));
        verifyNoInteractions(analysis, failures);
    }

    @Test void analysisFailureRemainsAvailableForDurableRetry() {
        message();
        doThrow(new IllegalStateException("upstream failure")).when(analysis).process(any(), eq(scope));
        assertThrows(IllegalStateException.class, () -> handler.execute(context, "payload"));
        verifyNoInteractions(failures);
    }

    @Test void exhaustionMarksOnlyMessageScope() {
        message();
        handler.onExhausted(context, "payload", new RuntimeException("exhausted"));
        verify(failures).markFailed(eq(scope), any(LocalDateTime.class));
    }

    @Test void malformedMessageCannotMarkUnrelatedRun() {
        when(codec.decodeIndicator("payload")).thenThrow(new IllegalArgumentException("bad payload"));
        handler.onExhausted(context, "payload", new RuntimeException());
        verifyNoInteractions(failures, analysis);
    }
}
