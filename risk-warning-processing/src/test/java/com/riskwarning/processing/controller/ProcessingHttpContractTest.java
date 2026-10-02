package com.riskwarning.processing.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.aspect.AuthAspect;
import com.riskwarning.common.utils.JwtUtils;
import com.riskwarning.common.exception.*;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.po.user.User;
import com.riskwarning.processing.dto.BehaviorListVO;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.EvidenceChunkRepository;
import com.riskwarning.processing.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际 Controller、Servlet 与异常响应验证；身份由测试过滤器控制，不调用外部基础设施。 */
@SpringBootTest(classes = ProcessingHttpContractTest.Server.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProcessingHttpContractTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;
    @MockBean private AnalysisOverviewService overview;
    @MockBean private AnalysisContextService context;
    @MockBean private BehaviorQueryService behaviors;
    @MockBean private JwtUtils jwt;
    @MockBean private EntityManager users;
    @Autowired private EvidenceChunkRepository evidence;
    @Autowired private AtomicInteger leakedContexts;



    @BeforeEach void prepareAuthentication() {
        when(jwt.getUserIdFromToken("Bearer controlled")).thenReturn(3L);
        when(jwt.getUserIdFromToken("Bearer invalid")).thenThrow(new IllegalArgumentException("invalid"));
        User actor = new User(); actor.setId(3L);
        when(users.find(User.class, 3L)).thenReturn(actor);
    }

    @Test void authenticationFailuresRemainUnauthorizedAcrossReadEndpoints() {
        for (String path : Arrays.asList("/analysis/overview/1?projectId=1", "/analysis/context/1?projectId=1",
                "/evidence?projectId=1&assessmentId=1", "/behavior?projectId=1&assessmentId=1")) {
            verify(http.getForEntity(url(path), JsonNode.class), 401);
            HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth("invalid");
            verify(http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class), 401);
        }
        verifyNoInteractions(overview, context, behaviors);
    }
    @Test void deletedActorIsUnauthorizedAndUserStoreFailureRemainsSystemError() {
        when(users.find(User.class, 3L)).thenReturn(null);
        verify(get("/analysis/overview/1?projectId=1"), 401);
        when(users.find(User.class, 3L)).thenThrow(new IllegalStateException("user store unavailable"));
        verify(get("/analysis/overview/1?projectId=1"), 500);
        verifyNoInteractions(overview);
    }
    @AfterEach void noThreadContextLeaks() { assertEquals(0, leakedContexts.get()); }
    @Test void bindingFailuresUseBadRequestAcrossControllers() {
        verify(get("/analysis/overview/not-number?projectId=1"), 400);
        verify(get("/analysis/context/1?projectId=not-number"), 400);
        verify(get("/evidence?assessmentId=1"), 400);
        verify(get("/behavior?projectId=1"), 400);
        verify(get("/unknown-processing-route"), 404);
    }
    private void failure(Long id) {
        switch (id.intValue()) {
            case 2: throw new IllegalArgumentException("参数无效");
            case 3: throw new SecurityException("作用域不符");
            case 4: throw new NoSuchElementException("对象不存在");
            case 5: throw new IllegalStateException("内部记录损坏");
            case 6: throw new AnalysisInputConflictException("输入冲突");
            default: break;
        }
    }
    private ResponseEntity<JsonNode> get(String path) {
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth("controlled");
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }
    private String url(String path) { return "http://localhost:" + port + path; }
    private void verify(ResponseEntity<JsonNode> response, int expected) {
        assertEquals(expected, response.getStatusCodeValue()); assertNotNull(response.getBody());
        assertEquals(expected, response.getBody().path("code").asInt());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) assertTrue(response.getBody().path("data").isNull());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({GlobalExceptionHandler.class, ApiErrorController.class, AnalysisOverviewController.class, AnalysisContextController.class,
            EvidenceQueryController.class, BehaviorQueryController.class})
    static class Server {
        @Bean EvidenceChunkRepository evidence() { return mock(EvidenceChunkRepository.class); }
        @Bean EvidenceQueryService evidenceService(EvidenceChunkRepository repository) { return new EvidenceQueryService(repository); }
        @Bean AuthAspect auth(JwtUtils jwt, EntityManager users) {
            AuthAspect aspect = new AuthAspect(); ReflectionTestUtils.setField(aspect, "jwtUtils", jwt);
            ReflectionTestUtils.setField(aspect, "em", users); return aspect;
        }
        @Bean EntityManagerFactory entityManagerFactory(EntityManager users) {
            EntityManagerFactory factory = mock(EntityManagerFactory.class);
            when(factory.getProperties()).thenReturn(Collections.emptyMap());
            when(factory.createEntityManager()).thenReturn(users);
            when(factory.createEntityManager(anyMap())).thenReturn(users);
            when(factory.isOpen()).thenReturn(true);
            return factory;
        }
        @Bean AtomicInteger leakedContexts() { return new AtomicInteger(); }
        @Bean FilterRegistrationBean<OncePerRequestFilter> contextObserver(AtomicInteger leakedContexts) {
            return new FilterRegistrationBean<>(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    try { chain.doFilter(request, response); }
                    finally { if (UserContext.getUser() != null) leakedContexts.incrementAndGet(); UserContext.clear(); }
                }
            });
        }
    }
}
