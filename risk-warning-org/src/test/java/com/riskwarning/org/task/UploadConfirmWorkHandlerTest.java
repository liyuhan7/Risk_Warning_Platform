package com.riskwarning.org.task;

import com.riskwarning.common.reliability.DurableWork;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.service.UploadConfirmCleanup;
import com.riskwarning.org.service.UploadConfirmProcessor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadConfirmWorkHandlerTest {

    private final UploadConfirmTaskCodec codec = new UploadConfirmTaskCodec();
    private final UploadConfirmProcessor processor = mock(UploadConfirmProcessor.class);
    private final UploadConfirmCleanup cleanup = mock(UploadConfirmCleanup.class);
    private final UploadConfirmWorkHandler handler =
            new UploadConfirmWorkHandler(codec, processor, cleanup);

    @Test
    void exposesUploadConfirmKind() {
        assertEquals("UPLOAD_CONFIRM", handler.kind());
    }

    @Test
    void decodesPayloadProcessesAndCleansUp() {
        UploadConfirmDto task = task();
        when(processor.process(task))
                .thenReturn(UploadConfirmProcessor.UploadConfirmOutcome.created(9L));

        handler.execute(context(), codec.encode(task));

        verify(processor).process(task);
        verify(cleanup).cleanup(task);
    }

    @Test
    void stillCleansUpWhenTaskWasAlreadyCompleted() {
        UploadConfirmDto task = task();
        when(processor.process(task))
                .thenReturn(UploadConfirmProcessor.UploadConfirmOutcome.alreadyCompleted(9L));

        handler.execute(context(), codec.encode(task));

        verify(cleanup).cleanup(task);
    }

    @Test
    void propagatesProcessorFailureWithoutCleanup() {
        UploadConfirmDto task = task();
        when(processor.process(task)).thenThrow(new IllegalStateException("merge failed"));

        assertThrows(IllegalStateException.class,
                () -> handler.execute(context(), codec.encode(task)));

        verify(cleanup, never()).cleanup(any());
    }

    @Test
    void rejectsInvalidPayloadBeforeProcessing() {
        assertThrows(IllegalStateException.class,
                () -> handler.execute(context(), "not-json"));

        verify(processor, never()).process(any());
        verify(cleanup, never()).cleanup(any());
    }

    @Test
    void flowContextCarriesUploadTaskIdentity() {
        UploadConfirmDto task = task();

        com.riskwarning.common.observability.AssessmentFlowContext flow =
                handler.flowContext(context(), codec.encode(task));

        assertEquals(Long.valueOf(7L), flow.getProjectId());
        assertEquals("upload:7:abc", flow.getTaskId());
        assertEquals("upload:7:abc", flow.getMessageId());
    }

    private DurableWorkContext context() {
        return new DurableWorkContext(new DurableWork("work-1", "risk-warning-org",
                UploadConfirmWorkHandler.KIND, "upload:7:abc", "payload", 1, "worker-1",
                "lease-1", LocalDateTime.now().plusMinutes(15)));
    }

    private UploadConfirmDto task() {
        UploadFileDto file = UploadFileDto.builder()
                .projectId(7L).uploadId("u-1").userId(8L)
                .filePath("/tmp/u-1").totalChunks(1).fileSuffix("pdf").build();
        return UploadConfirmDto.builder()
                .taskId("upload:7:abc").projectId(7L).userId(8L)
                .files(Collections.singletonList(file)).build();
    }
}

