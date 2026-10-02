package com.riskwarning.org.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.aspect.AuthAspect;
import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.exception.*;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.*;
import com.riskwarning.org.repository.UserRepository;
import com.riskwarning.org.service.UserService;
import com.riskwarning.org.service.impl.UserServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.persistence.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实用户 Controller、Service 与认证切面的 HTTP 验证，存储、密码和令牌为替身。 */
@SpringBootTest(classes = UserHttpContractTest.Server.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserHttpContractTest {
    @LocalServerPort private int port;
    @Autowired private TestRestTemplate http;
    @MockBean private UserRepository repository;
    @MockBean private PasswordUtils passwords;
    @MockBean private JwtUtils jwt;
    @MockBean private EntityManager users;
    @Autowired private AtomicInteger leakedContexts;

    @BeforeEach void setup() {
        leakedContexts.set(0); User user = new User(); user.setId(3L); user.setEmail("known@example.com");
        user.setFullName("Known"); user.setPassword("encoded");
        when(repository.findByEmail("known@example.com")).thenReturn(Optional.of(user));
        when(repository.findById(3L)).thenReturn(Optional.of(user));
        when(repository.existsByEmail("known@example.com")).thenReturn(true);
        when(repository.save(any(User.class))).thenAnswer(call -> { User saved = call.getArgument(0); if (saved.getId() == null) saved.setId(4L); return saved; });
        when(passwords.encodePassword(anyString())).thenReturn("encoded");
        when(passwords.matches("validpass", "encoded")).thenReturn(true);
        when(jwt.generateToken(anyLong(), anyString(), anyString())).thenReturn("controlled-token");
        when(jwt.getUserIdFromToken("Bearer controlled")).thenReturn(3L);
        when(jwt.getUserIdFromToken("Bearer invalid")).thenThrow(new RuntimeException("invalid token"));
        when(users.find(User.class, 3L)).thenReturn(user);
    }
    @Test void loginPreservesSuccessAndCredentialErrors() {
        ResponseEntity<JsonNode> success = post("/user/login", "{\"email\":\"known@example.com\",\"password\":\"validpass\"}");
        verify(success, 200); assertEquals("controlled-token", success.getBody().path("data").path("token").asText());
        verify(post("/user/login", "{\"email\":\"known@example.com\",\"password\":\"wrongpass\"}"), 401);
        verify(post("/user/login", "{\"email\":\"missing@example.com\",\"password\":\"validpass\"}"), 401);
    }
    @Test void registerDuplicateIsConflictAndFreshUserSucceeds() {
        verify(post("/user/register", "{\"email\":\"known@example.com\",\"password\":\"validpass\",\"fullName\":\"Known\"}"), 409);
        org.mockito.Mockito.verify(repository, never()).save(any());
        verify(post("/user/register", "{\"email\":\"fresh@example.com\",\"password\":\"validpass\",\"fullName\":\"Fresh\"}"), 200);
        org.mockito.Mockito.verify(repository).save(any(User.class));
    }
    @Test void validationAndMalformedBodyAreBadRequestBeforeService() {
        verify(post("/user/login", "{}"), 400); verify(post("/user/login", "{"), 400);
        verify(post("/user/register", "{\"email\":\"bad\",\"password\":\"short\",\"fullName\":\"\"}"), 400);
        verifyNoInteractions(repository, passwords, jwt);
    }
    @Test void publicQueriesDistinguishMissingObjectAndBadParameter() {
        verify(get("/user/3", null), 200); verify(get("/user/99", null), 404); verify(get("/user/-1", null), 400);
        verify(get("/user/email?email=known@example.com", null), 200);
        verify(get("/user/email?email=missing@example.com", null), 404);
        verify(get("/user/email?email=", null), 400); verify(get("/user/email", null), 400);
    }
    @Test void profileRequiresAuthenticatedActorAndUpdatesOwnedUser() {
        verify(get("/user/profile", null), 401); verify(get("/user/profile", "invalid"), 401);
        verify(get("/user/profile", "controlled"), 200);
        HttpHeaders headers = jsonHeaders(); headers.setBearerAuth("controlled");
        verify(http.exchange(url("/user/profile"), HttpMethod.PUT, new HttpEntity<>("{\"fullName\":\"Updated\"}", headers), JsonNode.class), 200);
        org.mockito.Mockito.verify(repository).save(argThat((User user) -> user.getId() == 3L && "Updated".equals(user.getFullName())));
    }
    @Test void repositoryFailureStaysSystemErrorAndDoesNotLeakDetails() {
        when(repository.findByEmail("known@example.com")).thenThrow(new IllegalStateException("internal storage detail"));
        ResponseEntity<JsonNode> failure = post("/user/login", "{\"email\":\"known@example.com\",\"password\":\"validpass\"}");
        verify(failure, 500); assertFalse(failure.getBody().path("message").asText().contains("internal storage detail"));
    }
    @AfterEach void actorContextCleared() { assertEquals(0, leakedContexts.get()); }
    private ResponseEntity<JsonNode> post(String path, String json) { return http.postForEntity(url(path), new HttpEntity<>(json, jsonHeaders()), JsonNode.class); }
    private ResponseEntity<JsonNode> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders(); if (token != null) headers.setBearerAuth(token);
        return http.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }
    private HttpHeaders jsonHeaders() { HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); return headers; }
    private String url(String path) { return "http://localhost:" + port + path; }
    private void verify(ResponseEntity<JsonNode> response, int expected) {
        assertEquals(expected, response.getStatusCodeValue()); assertNotNull(response.getBody());
        assertEquals(expected, response.getBody().path("code").asInt());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) assertTrue(response.getBody().path("data").isNull());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, ValidationAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({UserController.class, GlobalExceptionHandler.class, ApiErrorController.class})
    static class Server {
        @Bean UserService userService(UserRepository repository, PasswordUtils passwords, JwtUtils jwt) { return new UserServiceImpl(repository, passwords, jwt); }
        @Bean AuthAspect auth(JwtUtils jwt, EntityManager users) {
            AuthAspect aspect = new AuthAspect(); ReflectionTestUtils.setField(aspect, "jwtUtils", jwt); ReflectionTestUtils.setField(aspect, "em", users); return aspect;
        }
        @Bean EntityManagerFactory entityManagerFactory(EntityManager users) {
            EntityManagerFactory factory = mock(EntityManagerFactory.class); when(factory.getProperties()).thenReturn(Collections.emptyMap());
            when(factory.createEntityManager()).thenReturn(users); when(factory.createEntityManager(anyMap())).thenReturn(users); when(factory.isOpen()).thenReturn(true); return factory;
        }
        @Bean AtomicInteger leakedContexts() { return new AtomicInteger(); }
        @Bean FilterRegistrationBean<OncePerRequestFilter> contextObserver(AtomicInteger leakedContexts) {
            return new FilterRegistrationBean<>(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                    try { chain.doFilter(request, response); }
                    finally { if (UserContext.getUser() != null) leakedContexts.incrementAndGet(); UserContext.clear(); }
                }
            });
        }
    }
}
