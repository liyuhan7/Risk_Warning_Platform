package com.riskwarning.org.service.impl;

import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.FileRepository;
import com.riskwarning.org.task.UploadTaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FileServiceImplTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void savesOriginalFileNameInRedisUploadTask() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.sHasKey(anyString(), any())).thenReturn(false);
        when(redisUtil.sGetSetSize(anyString())).thenReturn(0L);
        when(redisUtil.sSetAndTime(anyString(), anyLong(), (Object[]) any())).thenReturn(1L);
        FileServiceImpl service = service(redisUtil);
        User user = new User();
        user.setId(88L);
        UserContext.setUser(user);

        service.initUpload(10L, "file-hash", 1024L, 1, "pdf", "企业管理制度.pdf");

        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(redisUtil).hset(anyString(), anyString(), value.capture(), anyLong());
        assertEquals("企业管理制度.pdf", ((UploadFileDto) value.getValue()).getOriginalFileName());
    }

    @Test
    void rejectsOriginalNameContainingPath() {
        assertThrows(BusinessException.class, () -> service(mock(RedisUtil.class))
                .initUpload(10L, "file-hash", 1024L, 1, "pdf", "../企业管理制度.pdf"));
    }

    @Test
    void confirmsUploadThroughReliableQueueBoundary() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        UploadTaskQueue queue = mock(UploadTaskQueue.class);
        Long projectId = 79L;
        String uploadId = "upload-confirm";
        UploadFileDto upload = UploadFileDto.builder()
                .projectId(projectId).uploadId(uploadId).userId(88L)
                .filePath("/tmp/upload-confirm").fileHash("hash-1")
                .totalChunks(1).fileSuffix("pdf").originalFileName("制度.pdf").build();
        when(redisUtil.hmget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId)))
                .thenReturn(Collections.singletonMap(uploadId, upload));
        when(redisUtil.sGet(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId)))
                .thenReturn(Collections.singleton(0));
        User user = new User();
        user.setId(88L);
        UserContext.setUser(user);
        FileServiceImpl service = service(redisUtil, queue);

        service.confirmUpload(projectId);

        ArgumentCaptor<UploadConfirmDto> task = ArgumentCaptor.forClass(UploadConfirmDto.class);
        verify(queue).enqueue(task.capture());
        assertEquals(projectId, task.getValue().getProjectId());
        assertEquals(Long.valueOf(88L), task.getValue().getUserId());
        // 快照随任务入队，稳定 taskId 由 projectId + sorted(uploadIds) 派生
        assertEquals(StringUtils.deriveUploadTaskId(projectId, Collections.singletonList(uploadId)),
                task.getValue().getTaskId());
        assertEquals(1, task.getValue().getFiles().size());
        assertEquals(uploadId, task.getValue().getFiles().get(0).getUploadId());
        assertEquals("制度.pdf", task.getValue().getFiles().get(0).getOriginalFileName());
        verify(redisUtil, never()).lSet(anyString(), any());
    }

    @Test
    void rejectsConfirmWhenChunksIncomplete() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        UploadTaskQueue queue = mock(UploadTaskQueue.class);
        Long projectId = 80L;
        String uploadId = "upload-incomplete";
        UploadFileDto upload = UploadFileDto.builder()
                .projectId(projectId).uploadId(uploadId).totalChunks(2).build();
        when(redisUtil.hmget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId)))
                .thenReturn(Collections.singletonMap(uploadId, upload));
        when(redisUtil.sGet(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId)))
                .thenReturn(Collections.singleton(0));
        User user = new User();
        user.setId(88L);
        UserContext.setUser(user);
        FileServiceImpl service = service(redisUtil, queue);

        assertThrows(BusinessException.class, () -> service.confirmUpload(projectId));
        verify(queue, never()).enqueue(any());
    }

    @Test
    void rejectsConfirmWhenUploadBelongsToOtherProject() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        UploadTaskQueue queue = mock(UploadTaskQueue.class);
        Long projectId = 81L;
        UploadFileDto foreign = UploadFileDto.builder()
                .projectId(999L).uploadId("upload-foreign").totalChunks(1).build();
        when(redisUtil.hmget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId)))
                .thenReturn(Collections.singletonMap("upload-foreign", foreign));
        when(redisUtil.sGet(anyString())).thenReturn(Collections.singleton(0));
        User user = new User();
        user.setId(88L);
        UserContext.setUser(user);
        FileServiceImpl service = service(redisUtil, queue);

        assertThrows(BusinessException.class, () -> service.confirmUpload(projectId));
        verify(queue, never()).enqueue(any());
    }

    @Test
    void removesHashMetadataChunkKeyAndTemporaryDirectoryByUploadId() throws Exception {
        RedisUtil redisUtil = mock(RedisUtil.class);
        Long projectId = 77L;
        String uploadId = "upload-id";
        String fileHash = "file-hash";
        UploadFileDto upload = UploadFileDto.builder()
                .projectId(projectId).uploadId(uploadId).fileHash(fileHash)
                .filePath(Constants.getTempFileDirPath(projectId, uploadId))
                .totalChunks(1).build();
        String redisFileKey = String.format(RedisKey.REDIS_KEY_FILE, projectId);
        String uploadInfoKey = String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId);
        String chunksKey = String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId);
        when(redisUtil.hget(uploadInfoKey, uploadId)).thenReturn(upload);
        when(redisUtil.sHasKey(redisFileKey, fileHash)).thenReturn(true);
        Path tempDir = Paths.get(upload.getFilePath());
        Files.createDirectories(tempDir);
        Files.write(tempDir.resolve("0"), new byte[]{1, 2, 3});

        try {
            assertDoesNotThrow(() -> service(redisUtil).deleteTempFile(projectId, uploadId));

            verify(redisUtil).sHasKey(redisFileKey, fileHash);
            verify(redisUtil, never()).sHasKey(redisFileKey, uploadId);
            verify(redisUtil).setRemove(redisFileKey, fileHash);
            verify(redisUtil).hdel(uploadInfoKey, uploadId);
            verify(redisUtil).del(chunksKey);
            assertFalse(Files.exists(tempDir));
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    @Test
    void removesUploadMetadataWhenHashSetHasAlreadyExpired() throws Exception {
        RedisUtil redisUtil = mock(RedisUtil.class);
        Long projectId = 78L;
        String uploadId = "upload-id-expired-hash";
        String fileHash = "expired-file-hash";
        UploadFileDto upload = UploadFileDto.builder()
                .projectId(projectId).uploadId(uploadId).fileHash(fileHash)
                .filePath(Constants.getTempFileDirPath(projectId, uploadId))
                .totalChunks(1).build();
        String redisFileKey = String.format(RedisKey.REDIS_KEY_FILE, projectId);
        String uploadInfoKey = String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId);
        String chunksKey = String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId);
        when(redisUtil.hget(uploadInfoKey, uploadId)).thenReturn(upload);
        when(redisUtil.sHasKey(redisFileKey, fileHash)).thenReturn(false);
        Path tempDir = Paths.get(upload.getFilePath());
        Files.createDirectories(tempDir);

        try {
            assertDoesNotThrow(() -> service(redisUtil).deleteTempFile(projectId, uploadId));

            verify(redisUtil, never()).setRemove(redisFileKey, fileHash);
            verify(redisUtil).hdel(uploadInfoKey, uploadId);
            verify(redisUtil).del(chunksKey);
            assertFalse(Files.exists(tempDir));
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private void deleteDirectory(File directory) {
        if (!directory.exists()) {
            return;
        }
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteDirectory(child);
                } else {
                    child.delete();
                }
            }
        }
        directory.delete();
    }

    private FileServiceImpl service(RedisUtil redisUtil) {
        return service(redisUtil, mock(UploadTaskQueue.class));
    }

    private FileServiceImpl service(RedisUtil redisUtil, UploadTaskQueue uploadTaskQueue) {
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(service, "fileRepository", mock(FileRepository.class));
        ReflectionTestUtils.setField(service, "uploadTaskQueue", uploadTaskQueue);
        ReflectionTestUtils.setField(service, "flowLogger", new AssessmentFlowLogger());
        return service;
    }
}
