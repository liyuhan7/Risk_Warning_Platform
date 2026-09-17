package com.riskwarning.org.service.impl;

import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.FileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(service, "fileRepository", mock(FileRepository.class));
        return service;
    }
}
