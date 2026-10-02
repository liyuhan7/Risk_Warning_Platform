package com.riskwarning.org.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.riskwarning.common.exception.*;
import com.riskwarning.common.po.enterprise.Enterprise;
import com.riskwarning.common.po.project.Project;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.user.User;
import com.riskwarning.org.repository.*;
import com.riskwarning.org.service.impl.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.*;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import javax.persistence.EntityManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 项目、企业公共查询及成员入口使用生产 Controller/Service，仓储为替身。 */
@SpringBootTest(classes = OrganizationHttpContractTest.Server.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.bootstrap.enabled=false", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"})
class OrganizationHttpContractTest {
    @Autowired private TestRestTemplate http;
    @Autowired private EnterpriseServiceImpl enterpriseService;
    @MockBean private ProjectRepository projects;
    @MockBean private ProjectMemberRepository projectMembers;
    @MockBean private AssessmentRepository assessments;
    @MockBean private EnterpriseRepository enterprises;
    @MockBean private EnterpriseUserRepository enterpriseMembers;
    @MockBean private EntityManager users;

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
    @Import({ProjectController.class, EnterpriseController.class, ProjectServiceImpl.class,
            EnterpriseServiceImpl.class, GlobalExceptionHandler.class, ApiErrorController.class})
    static class Server { }

    @BeforeEach void setup() {
        Project project = new Project(); project.setId(10L);
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(enterprises.findById(20L)).thenReturn(Optional.of(Enterprise.builder().id(20L).build()));
        User user = new User(); user.setId(3L); when(users.find(User.class, 3L)).thenReturn(user);
        when(projectMembers.findAll()).thenReturn(Collections.emptyList());
        when(enterpriseMembers.findAll()).thenReturn(Collections.emptyList());
    }
    @Test void memberQueriesDistinguishEmptySuccessMissingObjectAndInvalidId() {
        check(http.getForEntity("/project/10/members", JsonNode.class), 200);
        check(http.getForEntity("/enterprise/20/members", JsonNode.class), 200);
        check(http.getForEntity("/project/99/members", JsonNode.class), 404);
        check(http.getForEntity("/enterprise/99/members", JsonNode.class), 404);
        check(http.getForEntity("/project/0/members", JsonNode.class), 400);
        check(http.getForEntity("/enterprise/-1/members", JsonNode.class), 400);
    }
    @Test void assessmentLookupDistinguishesMissingProjectAndMissingAssessment() {
        check(http.getForEntity("/project/99/assessment", JsonNode.class), 404);
        check(http.getForEntity("/project/10/assessment", JsonNode.class), 404);
        when(assessments.findFirstByProjectIdOrderByIdDesc(10L))
                .thenReturn(Optional.of(Assessment.builder().id(30L).projectId(10L).build()));
        check(http.getForEntity("/project/10/assessment", JsonNode.class), 200);
        check(http.getForEntity("/project/0/assessment", JsonNode.class), 400);
    }
    @Test void memberWritesRequireExistingOrganizationAndUser() {
        post("/project/99/members", "{\"userId\":3,\"role\":\"viewer\"}", 404);
        post("/enterprise/99/members", "{\"userId\":3,\"role\":\"member\"}", 404);
        post("/project/10/members", "{\"userId\":99,\"role\":\"viewer\"}", 404);
        post("/enterprise/20/members", "{\"userId\":99,\"role\":\"member\"}", 404);
        verify(projectMembers, never()).save(any()); verify(enterpriseMembers, never()).save(any());
    }
    @Test void memberParametersAndRoleCodesAreBadRequestBeforeWrite() {
        post("/project/10/members", "{\"role\":\"viewer\"}", 400);
        post("/enterprise/20/members", "{\"userId\":0,\"role\":\"member\"}", 400);
        post("/project/10/members", "{\"userId\":3,\"role\":\"invalid\"}", 400);
        post("/enterprise/20/members", "{\"userId\":3,\"role\":\"invalid\"}", 400);
        verify(projectMembers, never()).save(any()); verify(enterpriseMembers, never()).save(any());
    }
    @Test void validMemberWritesPreserveRoleAndScope() {
        post("/project/10/members", "{\"userId\":3,\"role\":\"viewer\"}", 200);
        post("/enterprise/20/members", "{\"userId\":3,\"role\":\"member\"}", 200);
        verify(projectMembers).save(argThat(member -> member.getId().getProjectId().equals(10L)
                && member.getId().getUserId().equals(3L) && "viewer".equals(member.getRole().toString())));
        verify(enterpriseMembers).save(argThat(member -> member.getId().getEnterpriseId().equals(20L)
                && member.getId().getUserId().equals(3L) && "member".equals(member.getRole().toString())));
    }
    @Test void storageFailuresRemainSanitizedServerErrors() {
        when(projects.findById(10L)).thenThrow(new IllegalStateException("private-storage-detail"));
        when(enterprises.findById(20L)).thenThrow(new IllegalStateException("private-storage-detail"));
        check(http.getForEntity("/project/10/members", JsonNode.class), 500);
        check(http.getForEntity("/enterprise/20/members", JsonNode.class), 500);
    }
    @Test void duplicateCreditCodeIsExplicitServiceConflict() {
        when(enterprises.findByCreditCode("duplicate"))
                .thenReturn(Optional.of(Enterprise.builder().id(20L).build()));
        assertEquals(409, assertThrows(BusinessException.class,
                () -> enterpriseService.createEnterprise(Enterprise.builder().creditCode("duplicate").build())).getCode());
        verify(enterprises, never()).save(any());
    }
    private void post(String path, String json, int expected) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        check(http.postForEntity(path, new HttpEntity<>(json, headers), JsonNode.class), expected);
    }
    private void check(ResponseEntity<JsonNode> response, int expected) {
        assertEquals(expected, response.getStatusCodeValue()); assertNotNull(response.getBody());
        assertEquals(expected, response.getBody().path("code").asInt());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(response.getHeaders().getContentType()));
        if (expected >= 400) {
            assertTrue(response.getBody().has("data")); assertTrue(response.getBody().get("data").isNull());
            assertFalse(response.getBody().toString().contains("private-storage-detail"));
        }
    }
}
