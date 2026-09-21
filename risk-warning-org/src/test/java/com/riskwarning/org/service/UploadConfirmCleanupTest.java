package com.riskwarning.org.service;

import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadConfirmCleanupTest {

    private static final Long PROJECT_ID = 992002L;

    private final RedisUtil redisUtil = mock(RedisUtil.class);
    private final UploadConfirmCleanup cleanup = new UploadConfirmCleanup(redisUtil);

    @AfterEach
    void removeTempDirectories() {
        FileUtils.delDirectory(Constants.getTempFileDirPath(PROJECT_ID, "u-1"));
        FileUtils.delDirectory(Constants.getTempFileDirPath(PROJECT_ID, "u-2"));
    }

    @Test
    void removesRecoveryInputsByUploadId() throws Exception {
        Path dirOne = Paths.get(Constants.getTempFileDirPath(PROJECT_ID, "u-1"));
        Path dirTwo = Paths.get(Constants.getTempFileDirPath(PROJECT_ID, "u-2"));
        Files.createDirectories(dirOne);
        Files.write(dirOne.resolve("0"), new byte[]{1});
        Files.createDirectories(dirTwo);
        UploadFileDto withHash = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("u-1").fileHash("hash-1").build();
        UploadFileDto withoutHash = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("u-2").build();
        UploadConfirmDto task = UploadConfirmDto.builder()
                .projectId(PROJECT_ID).userId(8L)
                .files(Arrays.asList(withHash, withoutHash)).build();
        String uploadInfoKey = String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, PROJECT_ID);
        String fileHashKey = String.format(RedisKey.REDIS_KEY_FILE, PROJECT_ID);

        cleanup.cleanup(task);

        verify(redisUtil).hdel(uploadInfoKey, "u-1");
        verify(redisUtil).hdel(uploadInfoKey, "u-2");
        verify(redisUtil).del(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, "u-1"));
        verify(redisUtil).del(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, "u-2"));
        verify(redisUtil).setRemove(fileHashKey, "hash-1");
        verify(redisUtil, never()).setRemove(eq(fileHashKey), eq("u-2"));
        assertFalse(Files.exists(dirOne));
        assertFalse(Files.exists(dirTwo));
    }

    @Test
    void cleanupIsIdempotentForMissingEntries() {
        UploadFileDto file = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("u-1").fileHash("hash-1").build();
        UploadConfirmDto task = UploadConfirmDto.builder()
                .projectId(PROJECT_ID).userId(8L)
                .files(Arrays.asList(file)).build();

        assertDoesNotThrow(() -> cleanup.cleanup(task));
        assertDoesNotThrow(() -> cleanup.cleanup(task));
    }

    @Test
    void fallsBackToRedisMetadataForLegacyTask() {
        UploadFileDto legacy = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("u-1").fileHash("hash-1").build();
        Map<Object, Object> uploads = new HashMap<>();
        uploads.put("u-1", legacy);
        when(redisUtil.hmget(anyString())).thenReturn(uploads);
        UploadConfirmDto legacyTask = UploadConfirmDto.builder()
                .projectId(PROJECT_ID).userId(8L).build();

        cleanup.cleanup(legacyTask);

        verify(redisUtil).hdel(
                String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, PROJECT_ID), "u-1");
        verify(redisUtil).del(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, "u-1"));
        verify(redisUtil).setRemove(
                String.format(RedisKey.REDIS_KEY_FILE, PROJECT_ID), "hash-1");
    }
}
