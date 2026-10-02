package com.riskwarning.org.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.aspect.AuthAspect;
import com.riskwarning.common.exception.*;
import com.riskwarning.common.po.enterprise.Enterprise;
import com.riskwarning.common.po.project.Project;
import com.riskwarning.common.po.user.User;
import com.riskwarning.common.utils.JwtUtils;
import com.riskwarning.org.repository.*;
import com.riskwarning.org.service.impl.*;
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
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import javax.persistence.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 生产创建 Controller/Service/认证切面的真实 HTTP，令牌与存储为替身。 */
@SpringBootTest(classes = OrganizationCreationHttpTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"})
class OrganizationCreationHttpTest {
    @Autowired private TestRestTemplate http;
    @MockBean private ProjectRepository projects;
    @MockBean private ProjectMemberRepository projectMembers;
    @MockBean private AssessmentRepository assessments;
    @MockBean private EnterpriseRepository enterprises;
    @MockBean private EnterpriseUserRepository enterpriseMembers;
    @MockBean private EntityManager users;
    @MockBean private JwtUtils jwt;

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ValidationAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class})
    @Import({ProjectController.class, EnterpriseController.class, ProjectServiceImpl.class,
            EnterpriseServiceImpl.class, GlobalExceptionHandler.class, ApiErrorController.class, AuthAspect.class})
    static class Server {
        @Bean EntityManagerFactory entityManagerFactory(EntityManager users) {
            EntityManagerFactory factory = mock(EntityManagerFactory.class);
            when(factory.getProperties()).thenReturn(Collections.emptyMap());
            when(factory.createEntityManager()).thenReturn(users);
            when(factory.createEntityManager(anyMap())).thenReturn(users);
            when(factory.isOpen()).thenReturn(true); return factory;
        }
    }
    @BeforeEach void setup() {
        User user = new User(); user.setId(3L);
        when(jwt.getUserIdFromToken("Bearer controlled")).thenReturn(3L);
        when(jwt.getUserIdFromToken("Bearer invalid")).thenThrow(new IllegalArgumentException("private-token"));
        when(users.find(User.class, 3L)).thenReturn(user);
        when(projects.save(any())).thenAnswer(call -> { Project p = call.getArgument(0); p.setId(10L); return p; });
        when(enterprises.save(any())).thenAnswer(call -> { Enterprise e = call.getArgument(0); e.setId(20L); return e; });
    }
    @Test void validCreatesAssignAuthenticatedOwner() {
        post("/project", "{\"enterpriseId\":20,\"name\":\"项目\",\"type\":\"REGULAR\"}", "controlled", 200);
        post("/enterprise", "{\"name\":\"企业\",\"creditCode\":\"controlled-credit\"}", "controlled", 200);
        verify(projectMembers).save(argThat(member -> member.getId().getUserId().equals(3L)
                && member.getId().getProjectId().equals(10L)));
        verify(enterpriseMembers).save(argThat(member -> member.getId().getUserId().equals(3L)
                && member.getId().getEnterpriseId().equals(20L)));
    }
    @Test void validRequestsRequireAuthenticationBeforeWrite() {
        for (String token : new String[]{null, "invalid"}) {
            post("/project", "{\"enterpriseId\":20,\"name\":\"项目\"}", token, 401);
            post("/enterprise", "{\"name\":\"企业\",\"creditCode\":\"credit\"}", token, 401);
        }
        verify(projects, never()).save(any()); verify(enterprises, never()).save(any());
    }
    @Test void requiredFieldsAndEnumsAreBadRequestBeforeWrite() {
        for (String json : new String[]{"{}", "{\"enterpriseId\":0,\"name\":\"项目\"}",
                "{\"enterpriseId\":20,\"name\":\" \"}", "{\"enterpriseId\":20,\"name\":\"项目\",\"type\":\"invalid\"}"}) {
            post("/project", json, "controlled", 400);
        }
        for (String json : new String[]{"{}", "{\"name\":\"企业\",\"creditCode\":\" \"}", "{"}) {
            post("/enterprise", json, "controlled", 400);
        }
        verify(projects, never()).save(any()); verify(enterprises, never()).save(any());
    }
    @Test void duplicateCreditIsHttpConflictBeforeWrite() {
        when(enterprises.findByCreditCode("duplicate")).thenReturn(Optional.of(Enterprise.builder().id(20L).build()));
        post("/enterprise", "{\"name\":\"企业\",\"creditCode\":\"duplicate\"}", "controlled", 409);
        verify(enterprises, never()).save(any());
    }
    @Test void storageFailuresStaySanitizedServerErrors() {
        doThrow(new IllegalStateException("private-storage")).when(projects).save(any());
        doThrow(new IllegalStateException("private-storage")).when(enterprises).save(any());
        post("/project", "{\"enterpriseId\":20,\"name\":\"项目\"}", "controlled", 500);
        post("/enterprise", "{\"name\":\"企业\",\"creditCode\":\"credit\"}", "controlled", 500);
    }
    private void post(String path, String json, String token, int expected) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        ResponseEntity<JsonNode> response = http.postForEntity(path, new HttpEntity<>(json, headers), JsonNode.class);
        assertEquals(expected, response.getStatusCodeValue()); assertNotNull(response.getBody());
        assertEquals(expected, response.getBody().path("code").asInt());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) {
            assertTrue(response.getBody().has("data")); assertTrue(response.getBody().get("data").isNull());
            assertFalse(response.getBody().toString().contains("private-"));
        }
    }
}
