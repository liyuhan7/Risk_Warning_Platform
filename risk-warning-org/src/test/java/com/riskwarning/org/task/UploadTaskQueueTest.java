package com.riskwarning.org.task;

import com.riskwarning.common.reliability.redis.ClaimedRedisItem;
import com.riskwarning.common.reliability.redis.RedisClaimDeserializationException;
import com.riskwarning.common.reliability.redis.RedisDeadLetterItem;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadTaskQueueTest {

        @Test
        void usesConfiguredReadyProcessingAndDeadKeys() {
                RedisUtil redis = mock(RedisUtil.class);
                UploadTaskQueue queue = new UploadTaskQueue(redis, "upload:ready");
                UploadConfirmDto task = UploadConfirmDto.builder().projectId(7L).userId(8L).build();
                ClaimedRedisItem<UploadConfirmDto> claimed = new ClaimedRedisItem<>(
                                task, new byte[] { 1 }, "upload:ready", "upload:ready:processing");
                when(redis.lSet("upload:ready", task)).thenReturn(true);
                when(redis.claimListItem("upload:ready", "upload:ready:processing", UploadConfirmDto.class))
                                .thenReturn(claimed);

                queue.enqueue(task);
                ClaimedRedisItem<UploadConfirmDto> result = queue.claim();
                queue.acknowledge(result);

                assertSame(claimed, result);
                assertEquals("upload:ready:dead", queue.getDeadKey());
                verify(redis).acknowledgeListItem(claimed);
        }

        @Test
        void movesPoisonToDeadAndContinuesWithNextTask() {
                RedisUtil redis = mock(RedisUtil.class);
                UploadTaskQueue queue = new UploadTaskQueue(redis, "upload:ready");
                byte[] poison = new byte[] { 1, 2, 3 };
                UploadConfirmDto task = UploadConfirmDto.builder().projectId(7L).build();
                ClaimedRedisItem<UploadConfirmDto> valid = new ClaimedRedisItem<>(
                                task, new byte[] { 4 }, "upload:ready", "upload:ready:processing");
                when(redis.claimListItem("upload:ready", "upload:ready:processing", UploadConfirmDto.class))
                                .thenThrow(new RedisClaimDeserializationException(
                                                "upload:ready", "upload:ready:processing", poison,
                                                new IllegalStateException("poison")))
                                .thenReturn(valid);

                assertSame(valid, queue.claim());

                ArgumentCaptor<RedisDeadLetterItem> dead = ArgumentCaptor.forClass(RedisDeadLetterItem.class);
                verify(redis).deadLetterListItem(eq("upload:ready:processing"), eq("upload:ready:dead"),
                                eq(poison), dead.capture());
                assertEquals("upload:ready", dead.getValue().getQueueKey());
                assertEquals(64, dead.getValue().getPayloadHash().length());
                assertEquals(IllegalStateException.class.getName(), dead.getValue().getExceptionType());
        }

        @Test
        void rejectsFailedEnqueue() {
                RedisUtil redis = mock(RedisUtil.class);
                UploadTaskQueue queue = new UploadTaskQueue(redis, "upload:ready");
                UploadConfirmDto task = UploadConfirmDto.builder().projectId(7L).build();
                when(redis.lSet("upload:ready", task)).thenReturn(false);

                assertThrows(IllegalStateException.class, () -> queue.enqueue(task));
        }

        @Test
        void deadLettersUnrecoverableTaskWithReasonAndRawPayload() {
                RedisUtil redis = mock(RedisUtil.class);
                UploadTaskQueue queue = new UploadTaskQueue(redis, "upload:ready");
                byte[] raw = new byte[] { 5, 6 };
                UploadConfirmDto task = UploadConfirmDto.builder().projectId(7L).build();
                ClaimedRedisItem<UploadConfirmDto> claimed = new ClaimedRedisItem<>(
                                task, raw, "upload:ready", "upload:ready:processing");

                queue.deadLetter(claimed, "UploadSnapshotMissingException");

                ArgumentCaptor<RedisDeadLetterItem> dead = ArgumentCaptor.forClass(RedisDeadLetterItem.class);
                verify(redis).deadLetterListItem(eq("upload:ready:processing"), eq("upload:ready:dead"),
                                eq(raw), dead.capture());
                assertEquals("upload:ready", dead.getValue().getQueueKey());
                assertEquals("UploadSnapshotMissingException", dead.getValue().getExceptionType());
                assertEquals(64, dead.getValue().getPayloadHash().length());
        }
}
