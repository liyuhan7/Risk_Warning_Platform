package com.riskwarning.org.service.impl;

import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.FileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private FileServiceImpl service(RedisUtil redisUtil) {
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(service, "fileRepository", mock(FileRepository.class));
        return service;
    }
}
