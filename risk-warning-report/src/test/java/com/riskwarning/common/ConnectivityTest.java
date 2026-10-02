package com.riskwarning.common;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.config.ElasticSearchConfig;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.report.ReportApplication;
import com.riskwarning.report.repository.AssessmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 部署环境检查；依赖完整 Report 配置、隔离数据库及已有行为和风险测试语料。
 */
@EnabledIfSystemProperty(named = "report.environment.integration", matches = "true")
@SpringBootTest(classes = ReportApplication.class)
public class ConnectivityTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @BeforeEach
    void requireIsolatedDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertTrue(connection.getSchema() != null
                            && connection.getSchema().matches("report_it_[a-f0-9]{32}"),
                    "部署检查必须使用 report_it_<UUID> 隔离 schema");
        }
    }

    @Test
    @Transactional
    public void testAssessmentQuery() {
        Assessment assessment = assessmentRepository.saveAndFlush(Assessment.builder()
                .projectId(1L)
                .assessmentDate(LocalDateTime.now())
                .overallScore(0.0)
                .overallRiskLevel(RiskLevelEnum.LOW_RISK)
                .details("{}")
                .recommendations("")
                .status(AssessmentStatusEnum.ASSESSING)
                .createdAt(LocalDateTime.now())
                .build());
        assertNotNull(assessment.getId());
        Assessment reloaded = assessmentRepository.findById(assessment.getId())
                .orElseThrow(() -> new AssertionError("评估写入后必须可回读"));
        assertEquals(0.0, reloaded.getOverallScore());
        assertEquals(RiskLevelEnum.LOW_RISK, reloaded.getOverallRiskLevel());
        assertEquals("{}", reloaded.getDetails());
    }

    @Test
    public void testesConnect() throws Exception {
        SearchResponse<Behavior> response = client.search(s -> s
                        .index(ElasticSearchConfig.BEHAVIOR_INDEX)
                        .size(10)
                        .query(q -> q.term(t -> t.field("projectId").value(2))),
                Behavior.class);
        assertFalse(response.hits().hits().isEmpty(), "测试语料必须含 projectId=2 的行为");
        for (Hit<Behavior> hit : response.hits().hits()) {
            assertNotNull(hit.source());
            assertEquals(Long.valueOf(2L), hit.source().getProjectId());
        }
    }

    @Test
    public void testDiagnoseRiskLevelRawValue() throws Exception {
        SearchResponse<ObjectNode> response = client.search(s -> s
                        .index(ElasticSearchConfig.RISK_INDEX).size(5), ObjectNode.class);
        assertFalse(response.hits().hits().isEmpty(), "测试语料必须含风险文档");
        for (Hit<ObjectNode> hit : response.hits().hits()) {
            assertNotNull(hit.source());
            String level = hit.source().path("riskLevel").asText();
            assertFalse(level.isEmpty(), "风险等级必须按 camelCase 契约持久化");
            assertNotNull(RiskLevelEnum.fromValue(level), "风险等级必须为已定义枚举");
        }
    }

    @Test
    public void testDiagnoseRiskDeserialization() throws Exception {
        SearchResponse<Risk> response = client.search(s -> s
                        .index(ElasticSearchConfig.RISK_INDEX).size(5), Risk.class);
        assertFalse(response.hits().hits().isEmpty(), "测试语料必须含风险文档");
        for (Hit<Risk> hit : response.hits().hits()) {
            assertNotNull(hit.source());
            assertNotNull(hit.source().getRiskLevel(), "风险等级反序列化必须成功");
            assertNotNull(hit.source().getProjectId());
            assertNotNull(hit.source().getAssessmentId());
        }
    }
}
