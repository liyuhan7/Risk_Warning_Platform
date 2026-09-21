package com.riskwarning.common.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringUtilsTest {

    @Test
    void generatesUniqueUuidMessageAndTraceIdsConcurrently() {
        Set<String> messageIds = ConcurrentHashMap.newKeySet();
        Set<String> traceIds = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 10_000).parallel().forEach(index -> {
            messageIds.add(StringUtils.generateMessageId());
            traceIds.add(StringUtils.generateTraceId());
        });

        assertEquals(10_000, messageIds.size());
        assertEquals(10_000, traceIds.size());
        assertTrue(messageIds.stream().allMatch(id -> isUuidWithPrefix(id, "msg_")));
        assertTrue(traceIds.stream().allMatch(id -> isUuidWithPrefix(id, "trace_")));
    }

    @Test
    void generatesStableFileNameForSameUpload() {
        assertEquals("7_upload-1", StringUtils.generateFileName(7L, "upload-1"));
        assertEquals(StringUtils.generateFileName(7L, "upload-1"),
                StringUtils.generateFileName(7L, "upload-1"));
        assertThrows(IllegalArgumentException.class,
                () -> StringUtils.generateFileName(7L, " "));
    }

    @Test
    void derivesStableDownstreamMessageId() {
        assertEquals("message-1:indicator",
                StringUtils.deriveMessageId("message-1", "indicator"));
        assertEquals(StringUtils.deriveMessageId("message-1", "indicator"),
                StringUtils.deriveMessageId("message-1", "indicator"));
        assertThrows(IllegalArgumentException.class,
                () -> StringUtils.deriveMessageId(" ", "indicator"));
    }

    @Test
    void derivesStableUploadTaskIdFromSortedUploadIds() {
        String taskA = StringUtils.deriveUploadTaskId(7L, Arrays.asList("u-2", "u-1"));
        String taskB = StringUtils.deriveUploadTaskId(7L, Arrays.asList("u-1", "u-2"));

        assertEquals(taskA, taskB);
        assertTrue(taskA.startsWith("upload:7:"));
        // task_key 上限 160，taskId 必须留出派生下游消息 ID 的余量
        assertTrue(taskA.length() <= 96);
        assertNotEquals(taskA, StringUtils.deriveUploadTaskId(8L, Arrays.asList("u-1", "u-2")));
        assertNotEquals(taskA, StringUtils.deriveUploadTaskId(7L, Arrays.asList("u-1", "u-2", "u-3")));
    }

    @Test
    void rejectsInvalidUploadTaskIdInput() {
        assertThrows(IllegalArgumentException.class,
                () -> StringUtils.deriveUploadTaskId(null, Arrays.asList("u-1")));
        assertThrows(IllegalArgumentException.class,
                () -> StringUtils.deriveUploadTaskId(7L, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> StringUtils.deriveUploadTaskId(7L, Arrays.asList("u-1", " ")));
    }

    private boolean isUuidWithPrefix(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return false;
        }
        try {
            UUID.fromString(value.substring(prefix.length()));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
