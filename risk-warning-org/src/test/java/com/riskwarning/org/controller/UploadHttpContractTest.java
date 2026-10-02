package com.riskwarning.org.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.JwtUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.*;
import com.riskwarning.org.service.impl.FileServiceImpl;
import com.riskwarning.org.task.UploadTaskQueue;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import javax.persistence.EntityManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实上传 Controller/Service/认证切面 HTTP；Redis、队列、令牌与数据库为替身。 */
@SpringBootTest(classes = UploadHttpContractTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"})
@MockBean(classes = {ProjectRepository.class, ProjectMemberRepository.class, AssessmentRepository.class,
        EnterpriseRepository.class, EnterpriseUserRepository.class, FileRepository.class, AssessmentFlowLogger.class})
class UploadHttpContractTest {
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration(
            org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration.class)
    @Import({OrganizationCreationHttpTest.Server.class, FileController.class, FileServiceImpl.class})
    static class Server { }
    @Autowired private TestRestTemplate http;
    @MockBean private RedisUtil redis;
    @MockBean private UploadTaskQueue queue;
    @MockBean private EntityManager users;
    @MockBean private JwtUtils jwt;
    private static final String INFO = String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, 10L);

    @BeforeEach void setup() {
        User user = new User(); user.setId(3L); when(users.find(User.class, 3L)).thenReturn(user);
        when(jwt.getUserIdFromToken("Bearer controlled")).thenReturn(3L);
        when(redis.sSetAndTime(anyString(), anyLong(), (Object[]) any())).thenReturn(1L);
        when(redis.hset(anyString(), anyString(), any(), anyLong())).thenReturn(true);
    }
    @Test void initializationSuccessValidationAndDuplicateAreDistinct() {
        post("/file/initUpload", init("pdf", "制度.pdf", 1), "controlled", 200);
        post("/file/initUpload", init("exe", "制度.exe", 1), "controlled", 400);
        post("/file/initUpload", init("pdf", "../制度.pdf", 1), "controlled", 400);
        post("/file/initUpload", init("pdf", "制度.pdf", 0), "controlled", 400);
        when(redis.sHasKey(anyString(), any())).thenReturn(true);
        post("/file/initUpload", init("pdf", "制度.pdf", 1), "controlled", 409);
    }
    @Test void uploadRequestsRequireAuthenticatedIdentity() {
        post("/file/initUpload", init("pdf", "制度.pdf", 1), null, 401);
        post("/file/confirmUpload?projectId=10", null, null, 401);
        verifyNoInteractions(redis, queue);
    }
    @Test void confirmationDistinguishesMissingForeignIncompleteAndReadySnapshots() {
        post("/file/confirmUpload?projectId=10", null, "controlled", 404);
        UploadFileDto upload = upload(99L);
        when(redis.hmget(INFO)).thenReturn(Collections.singletonMap("upload", upload));
        post("/file/confirmUpload?projectId=10", null, "controlled", 403);
        upload.setProjectId(10L);
        post("/file/confirmUpload?projectId=10", null, "controlled", 409);
        when(redis.sGet(anyString())).thenReturn(Collections.singleton(0));
        post("/file/confirmUpload?projectId=10", null, "controlled", 200);
        verify(queue).enqueue(argThat(task -> task.getUserId().equals(3L)
                && task.getProjectId().equals(10L) && task.getFiles().size() == 1));
    }
    @Test void multipartChunkBindingMissingTaskScopeAndDuplicateAreExplicit() {
        chunk(0, 404);
        when(redis.hget(INFO, "upload")).thenReturn(upload(99L)); chunk(0, 403);
        when(redis.hget(INFO, "upload")).thenReturn(upload(10L)); chunk(1, 400); chunk(-1, 400);
        doReturn(0L).when(redis).sSetAndTime(anyString(), anyLong(), (Object[]) any()); chunk(0, 409);
    }
    @Test void invalidConfirmationAndDeleteIdentifiersAreRejectedBeforeStorage() {
        post("/file/confirmUpload?projectId=0", null, "controlled", 400);
        post("/file/deleteFile?projectId=10&uploadId=..", null, "controlled", 400);
        verifyNoInteractions(redis, queue);
    }
    @Test void redisAndQueueFailuresRemainServerErrors() {
        when(redis.hmget(INFO)).thenThrow(new IllegalStateException("private-storage"));
        post("/file/confirmUpload?projectId=10", null, "controlled", 500);
        doReturn(Collections.singletonMap("upload", upload(10L))).when(redis).hmget(INFO);
        when(redis.sGet(anyString())).thenReturn(Collections.singleton(0));
        doThrow(new IllegalStateException("private-queue")).when(queue).enqueue(any());
        post("/file/confirmUpload?projectId=10", null, "controlled", 500);
    }
    @Test void metadataWriteFailureDoesNotReportInitializationSuccessAndReleasesFileMarker() {
        when(redis.hset(anyString(), anyString(), any(), anyLong())).thenReturn(false);
        post("/file/initUpload", init("pdf", "制度.pdf", 1), "controlled", 500);
        verify(redis).setRemove(String.format(RedisKey.REDIS_KEY_FILE, 10L), "hash");
    }
    @Test void deletionDistinguishesMissingTaskMalformedMetadataAndSuccessfulCleanup() {
        String id = "delete-http-" + UUID.randomUUID();
        java.nio.file.Path target = java.nio.file.Paths.get(
                com.riskwarning.common.constants.Constants.getTempFileDirPath(10L, id));
        assertFalse(java.nio.file.Files.exists(target));
        String path = "/file/deleteFile?projectId=10&uploadId=" + id;
        post(path, null, "controlled", 404);
        UploadFileDto metadata = UploadFileDto.builder().projectId(10L).uploadId(id).build();
        when(redis.hget(INFO, id)).thenReturn(metadata);
        post(path, null, "controlled", 500);
        metadata.setFileHash("delete-test-hash");
        when(redis.sHasKey(String.format(RedisKey.REDIS_KEY_FILE, 10L), "delete-test-hash")).thenReturn(true);
        post(path, null, "controlled", 200);
        verify(redis).setRemove(String.format(RedisKey.REDIS_KEY_FILE, 10L), "delete-test-hash");
        verify(redis).hdel(INFO, id);
        verify(redis, times(3)).del(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, id));
        assertFalse(java.nio.file.Files.exists(target));
    }
    @Test void deletionCleanupFailureIsServerError() {
        String id = "delete-http-" + UUID.randomUUID();
        assertFalse(java.nio.file.Files.exists(java.nio.file.Paths.get(
                com.riskwarning.common.constants.Constants.getTempFileDirPath(10L, id))));
        doThrow(new IllegalStateException("private-cleanup"))
                .when(redis).del(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, id));
        post("/file/deleteFile?projectId=10&uploadId=" + id, null, "controlled", 500);
    }
    private UploadFileDto upload(Long projectId) {
        return UploadFileDto.builder().projectId(projectId).uploadId("upload").totalChunks(1)
                .fileHash("hash").filePath("unused").originalFileName("制度.pdf").fileSuffix("pdf").build();
    }
    private String init(String type, String name, int chunks) {
        return "{\"projectId\":10,\"fileHash\":\"hash\",\"fileSize\":100,\"totalChunks\":"
                + chunks + ",\"fileType\":\"" + type + "\",\"fileName\":\"" + name + "\"}";
    }
    private void chunk(int index, int expected) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("projectId", "10"); form.add("uploadId", "upload"); form.add("chunkIndex", String.valueOf(index));
        form.add("chunkData", new ByteArrayResource(new byte[]{1}) {
            @Override public String getFilename() { return "chunk.bin"; }
        });
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth("controlled"); check(http.postForEntity("/file/uploadChunk", new HttpEntity<>(form, headers), JsonNode.class), expected);
    }
    private void post(String path, Object body, String token, int expected) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        check(http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class), expected);
    }
    private void check(ResponseEntity<JsonNode> response, int expected) {
        assertEquals(expected, response.getStatusCodeValue(), String.valueOf(response.getBody())); assertNotNull(response.getBody());
        assertEquals(expected, response.getBody().path("code").asInt());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) {
            assertTrue(response.getBody().has("data")); assertTrue(response.getBody().get("data").isNull());
            assertFalse(response.getBody().toString().contains("private-"));
        }
    }
}
