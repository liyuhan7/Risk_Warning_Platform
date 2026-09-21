package com.riskwarning.org.task;

import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadRedisHandoffWorkerTest {

        private final UploadTaskQueue queue = mock(UploadTaskQueue.class);
        private final DurableWorkStore store = mock(DurableWorkStore.class);
        private final RedisUtil redisUtil = mock(RedisUtil.class);
        private final UploadConfirmTaskCodec codec = new UploadConfirmTaskCodec();
        private final AssessmentFlowLogger flowLogger = mock(AssessmentFlowLogger.class);

        private UploadRedisHandoffWorker worker() {
                return new UploadRedisHandoffWorker(queue, store, codec, redisUtil, flowLogger);
        }

        private ClaimedRedisItem<UploadConfirmDto> claimed(UploadConfirmDto task) {
                return new ClaimedRedisItem<>(task, new byte[] { 1 }, "upload:ready", "upload:ready:processing");
        }

        @Test
        void handsOffClaimedTaskAndAcknowledges() {
                UploadConfirmDto task = completeTask();
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(task);
                when(queue.claim()).thenReturn(claimed);
                when(store.enqueue(anyString(), anyString(), anyString())).thenReturn(true);

                assertTrue(worker().handoffOnce());

                verify(store).enqueue(eq(UploadConfirmWorkHandler.KIND), eq(task.getTaskId()),
                                contains(task.getTaskId()));
                verify(queue).acknowledge(claimed);

                org.mockito.ArgumentCaptor<AssessmentFlowEvent> flowCaptor = org.mockito.ArgumentCaptor
                                .forClass(AssessmentFlowEvent.class);
                verify(flowLogger, org.mockito.Mockito.times(2)).info(flowCaptor.capture());
                assertEquals(AssessmentFlowStage.UPLOAD_CLAIM,
                                flowCaptor.getAllValues().get(0).getStage());
                assertEquals(AssessmentFlowStatus.SUCCEEDED,
                                flowCaptor.getAllValues().get(0).getStatus());
                assertEquals(AssessmentFlowStage.UPLOAD_HANDOFF,
                                flowCaptor.getAllValues().get(1).getStage());
                assertEquals(AssessmentFlowStatus.SUCCEEDED,
                                flowCaptor.getAllValues().get(1).getStatus());
        }

        @Test
        void keepsTaskPendingWhenDurableEnqueueFails() {
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(completeTask());
                when(queue.claim()).thenReturn(claimed);
                when(store.enqueue(anyString(), anyString(), anyString()))
                                .thenThrow(new IllegalStateException("db down"));
                when(queue.recordFailure(anyString(), anyLong())).thenReturn(1L);

                worker().handoffOnce();

                verify(queue, never()).acknowledge(any());
                verify(queue, never()).deadLetter(any(), anyString());
        }

        @Test
        void attemptBelowCapStaysPendingWithoutDeadLetter() {
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(completeTask());
                when(queue.claim()).thenReturn(claimed);
                when(store.enqueue(anyString(), anyString(), anyString()))
                                .thenThrow(new IllegalStateException("db down"));
                when(queue.payloadHash(any())).thenReturn("hash-1");
                when(queue.recordFailure(anyString(), anyLong()))
                                .thenReturn(UploadRedisHandoffWorker.MAX_ENQUEUE_ATTEMPTS - 1L);

                worker().handoffOnce();

                verify(queue, never()).deadLetter(any(), anyString());
                verify(queue, never()).clearFailure(anyString());
        }

        @Test
        void persistentEnqueueFailureDeadLettersAtAttemptCap() {
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(completeTask());
                when(queue.claim()).thenReturn(claimed);
                when(store.enqueue(anyString(), anyString(), anyString()))
                                .thenThrow(new IllegalStateException("db down"));
                when(queue.payloadHash(any())).thenReturn("hash-1");
                when(queue.recordFailure(anyString(), anyLong()))
                                .thenReturn(UploadRedisHandoffWorker.MAX_ENQUEUE_ATTEMPTS + 0L);

                worker().handoffOnce();

                verify(queue).deadLetter(eq(claimed), eq(IllegalStateException.class.getSimpleName()));
                verify(queue).clearFailure(anyString());
                verify(queue, never()).acknowledge(any());
        }

        @Test
        void successfulHandoffClearsFailureCounter() {
                UploadConfirmDto task = completeTask();
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(task);
                when(queue.claim()).thenReturn(claimed);
                when(store.enqueue(anyString(), anyString(), anyString())).thenReturn(true);
                when(queue.payloadHash(any())).thenReturn("hash-1");

                assertTrue(worker().handoffOnce());

                verify(queue).acknowledge(claimed);
                verify(queue).clearFailure("hash-1");
        }

        @Test
        void duplicateEnqueueStillAcknowledges() {
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(completeTask());
                when(queue.claim()).thenReturn(claimed);
                when(store.enqueue(anyString(), anyString(), anyString())).thenReturn(false);

                worker().handoffOnce();

                verify(queue).acknowledge(claimed);
        }

        @Test
        void freezesLegacySnapshotFromRedisBeforeHandoff() {
                UploadConfirmDto legacy = UploadConfirmDto.builder().projectId(7L).userId(8L).build();
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(legacy);
                when(queue.claim()).thenReturn(claimed);
                Map<Object, Object> uploads = new HashMap<>();
                uploads.put("u-2", UploadFileDto.builder().projectId(7L).uploadId("u-2").build());
                uploads.put("u-1", UploadFileDto.builder().projectId(7L).uploadId("u-1").build());
                when(redisUtil.hmget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, 7L)))
                                .thenReturn(uploads);

                worker().handoffOnce();

                String derivedTaskId = StringUtils.deriveUploadTaskId(7L, Arrays.asList("u-1", "u-2"));
                ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
                verify(store).enqueue(eq(UploadConfirmWorkHandler.KIND), eq(derivedTaskId), payload.capture());
                assertTrue(payload.getValue().contains("u-1"));
                assertTrue(payload.getValue().contains("u-2"));
                assertTrue(payload.getValue().contains(derivedTaskId));
                verify(queue).acknowledge(claimed);
        }

        @Test
        void deadLettersLegacyTaskWhenMetadataMissing() {
                UploadConfirmDto legacy = UploadConfirmDto.builder().projectId(7L).userId(8L).build();
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(legacy);
                when(queue.claim()).thenReturn(claimed);
                when(redisUtil.hmget(anyString())).thenReturn(new HashMap<>());

                worker().handoffOnce();

                verify(queue).deadLetter(claimed, UploadSnapshotMissingException.class.getSimpleName());
                verify(store, never()).enqueue(anyString(), anyString(), anyString());
                verify(queue, never()).acknowledge(any());
        }

        @Test
        void deadLettersTaskWithoutProjectId() {
                ClaimedRedisItem<UploadConfirmDto> claimed = claimed(UploadConfirmDto.builder().userId(8L).build());
                when(queue.claim()).thenReturn(claimed);

                worker().handoffOnce();

                verify(queue).deadLetter(claimed, UploadSnapshotMissingException.class.getSimpleName());
                verify(store, never()).enqueue(anyString(), anyString(), anyString());
        }

        private UploadConfirmDto completeTask() {
                UploadFileDto file = UploadFileDto.builder()
                                .projectId(7L).uploadId("u-1").userId(8L)
                                .filePath("/tmp/u-1").totalChunks(1).fileSuffix("pdf").build();
                return UploadConfirmDto.builder()
                                .taskId("upload:7:abc").projectId(7L).userId(8L)
                                .files(Arrays.asList(file)).build();
        }
}
