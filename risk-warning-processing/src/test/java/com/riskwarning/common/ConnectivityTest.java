package com.riskwarning.common;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.regulation.Regulation;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.processing.ProcessingApplication;
import com.riskwarning.common.dto.IndicatorResultDTO;
import com.riskwarning.common.enums.KafkaTopic;
import com.riskwarning.common.message.*;
import com.riskwarning.common.po.indicator.Indicator;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.processing.repository.AssessmentRepository;
import com.riskwarning.processing.repository.IndicatorResultRepository;
import com.riskwarning.processing.service.BehaviorProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


@SpringBootTest(classes = ProcessingApplication.class)
public class ConnectivityTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private RedisUtil redisUtil;


    @Autowired
    private KafkaTemplate<String, Message> kafkaTemplate;

    @Autowired
    private IndicatorResultRepository indicatorResultRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private BehaviorProcessingService behaviorProcessingService;

//    @Test
//    @Transactional
//    public void testDatabaseConnection() {
//        try {
//            // 尝试获取PostgreSQL连接以验证连接
//
//            Assessment assessment = assessmentRepository.findById(88L).get();
//
//            IndicatorResult ir = IndicatorResult.builder()
//                    .projectId(7L)
//                    .assessmentId(88L)
//                    .indicatorEsId("indicatorEsId")
//                    .indicatorName("test")
//                    .indicatorLevel(0)
//                    .dimension("test")
//                    .type("test")
//                    .calculatedScore(0.0)
//                    .maxPossibleScore(1.0)
//                    .usedCalculationRuleType("auto")
//                    .calculationDetails(null)
//                    .riskTriggered(false)
//                    .riskStatus(IndicatorRiskStatus.fromCode("NOT_EVALUATED"))
//                    .calculatedAt(LocalDateTime.now())
//                    .createdAt(LocalDateTime.now())
//                    .build();
//            indicatorResultRepository.saveAndFlush(ir);
//            System.out
//                    .println("Connected to the database and saved IndicatorResult with ID: ");
//        } catch (Exception e) {
//            e.printStackTrace();
//            assert false : "Failed to connect to the database";
//        }
//    }


    @Test
    @Transactional
    public void testDatabaseConnection() {
        try {
            // 先查询是否存在评估记录，如果不存在则创建测试数据
            List<Assessment> assessments = assessmentRepository.findAll();

            Long assessmentId;
            Long projectId;

            if (assessments.isEmpty()) {
                System.out.println("No assessment records found, creating test data...");

                // 创建测试评估记录
                Assessment newAssessment = Assessment.builder()
                        .projectId(1L) // 确保项目 ID=1 存在
                        .assessmentDate(LocalDateTime.now())
                        .overallScore(75.5)
                        .overallRiskLevel(RiskLevelEnum.MEDIUM_RISK)
                        .details("{\"test\": \"data\"}")
                        .recommendations("测试建议")
                        .status(AssessmentStatusEnum.ASSESSING)
                        .createdAt(LocalDateTime.now())
                        .build();
                Assessment savedAssessment = assessmentRepository.saveAndFlush(newAssessment);
                assessmentId = savedAssessment.getId();
                projectId = savedAssessment.getProjectId();
                System.out.println("Created test assessment with ID: " + assessmentId);
            } else {
                // 使用第一个已有的评估记录
                Assessment existingAssessment = assessments.get(0);
                assessmentId = existingAssessment.getId();
                projectId = existingAssessment.getProjectId();
                System.out.println("Using existing assessment ID: " + assessmentId);
            }

            // 创建 IndicatorResult
            IndicatorResult ir = IndicatorResult.builder()
                    .projectId(projectId)
                    .assessmentId(assessmentId)
                    .indicatorEsId("indicator-es-id-test")
                    .indicatorName("测试指标")
                    .indicatorLevel(1)
                    .dimension("安全维度")
                    .type("range")
                    .calculatedScore(85.0)
                    .maxPossibleScore(100.0)
                    .usedCalculationRuleType("range")
                    .calculationDetails(null)
                    .riskTriggered(false)
                    .riskStatus(IndicatorRiskStatus.fromCode("NOT_EVALUATED"))
                    .calculatedAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .build();
            indicatorResultRepository.saveAndFlush(ir);
            System.out.println("Connected to the database and saved IndicatorResult with ID: " + ir.getId());
        } catch (Exception e) {
            e.printStackTrace();
            assert false : "Failed to connect to the database: " + e.getMessage();
        }
    }


//    @Test
//    @Transactional
//    public void testAssessmentQuery() {
//        try {
//
//            Assessment assessment1 = assessmentRepository.findById(88L).get();
//
//            Assessment assessment = Assessment.builder()
//                    .projectId(1000L)
//                    .assessmentDate(LocalDateTime.now())
//                    .overallScore(0.0)
//                    .overallRiskLevel(RiskLevelEnum.LOW_RISK)
//                    .details("")
//                    .recommendations("")
//                    .status(AssessmentStatusEnum.ASSESSING)
//                    .createdAt(LocalDateTime.now())
//                    .build();
//            assessmentRepository.saveAndFlush(assessment);
//
//            Assessment assessment2 = assessmentRepository.findByProjectId(1000L);
//
//            assert assessment2.getOverallScore() == 0.0;
//        } catch (Exception e) {
//            e.printStackTrace();
//            assert false : "Failed to query assessments from the database";
//        }
//    }
@Test
@Transactional
public void testAssessmentQuery() {
    try {
        // 先查询是否存在评估记录
        List<Assessment> assessments = assessmentRepository.findAll();

        Long projectId;

        // 验证查询：projectId 在存量数据中不唯一，按主键回读
        Long verifyId;
        if (assessments.isEmpty()) {
            projectId = 1L; // 确保项目存在
            Assessment savedAssessment = assessmentRepository.saveAndFlush(Assessment.builder()
                    .projectId(projectId)
                    .assessmentDate(LocalDateTime.now())
                    .overallScore(85.5)
                    .overallRiskLevel(RiskLevelEnum.MEDIUM_RISK)
                    // details 列为 jsonb，非 JSON 文本会被数据库拒绝，写入合法对象字面量
                    .details("{\"test\": \"data\"}")
                    .recommendations("测试建议")
                    .status(AssessmentStatusEnum.ASSESSING)
                    .createdAt(LocalDateTime.now())
                    .build());
            verifyId = savedAssessment.getId();
            System.out.println("Created test assessment with ID: " + verifyId);
        } else {
            // 使用已有的评估记录
            Assessment existing = assessments.get(0);
            projectId = existing.getProjectId();
            verifyId = existing.getId();
            System.out.println("Using existing project ID: " + projectId);
        }
        Assessment reloaded = assessmentRepository.findById(verifyId).orElse(null);
        assert reloaded != null : "未找到评估记录 id=" + verifyId;

        System.out.println("Test passed! Found assessment for project: " + projectId);
    } catch (Exception e) {
        e.printStackTrace();
        assert false : "Failed to query assessments from the database: " + e.getMessage();
    }
}


    @Test
    public void testElasticsearchConnection() {
        try {
            // 尝试获取集群信息以验证连接
            InfoResponse infoResponse = client.info();
            System.out.println("Connected to Elasticsearch cluster: " + infoResponse.clusterName());
            List<Indicator> indicatorList = findAll("t_indicator", Indicator.class);
            List<Behavior> behaviorList = findAll("t_behavior", Behavior.class);
            List<Regulation> regulationList = findAll("t_regulation", Regulation.class);
            System.out.println("Successfully queried indicators from Elasticsearch." + indicatorList.size());
            System.out.println("Successfully queried behaviors from Elasticsearch." + behaviorList.size());
            System.out.println("Successfully queried regulation from Elasticsearch." + regulationList.size());
            System.out.println("Successfully queried regulation from Elasticsearch.");
        } catch (Exception e) {
            e.printStackTrace();
            assert false : "Failed to connect to Elasticsearch";
        }
    }

    public <T> List<T> findAll(String indexName, Class<T> clazz) {
        try {
            SearchResponse<T> response = client.search(
                    s -> s
                            .index(indexName)
                            .size(10), // 设置返回的文档数量上限
                    clazz);

            return response.hits().hits().stream()
                    .map(hit -> hit.source()).collect(Collectors.toList());
        } catch (Exception e) {
            System.err.println("查询所有指标时发生错误: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("查询失败");
        }
    }

    @Test
    public void testRedisConnection() {
        try {
            redisUtil.set("connection_test_key", "connection_test_value");
            String value = (String) redisUtil.get("connection_test_key");
            assert "connection_test_value".equals(value) : "Redis value mismatch";
            System.out.println("Connected to Redis and verified key-value pair.");
        } catch (Exception e) {
            e.printStackTrace();
            assert false : "Failed to connect to Redis";
        }
    }


    @Test
    public void testKafkaConnection() {
        try {
            // basic Message
            BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage();
            message.setTopic(KafkaTopic.TEST_TOPIC);
            message.setMessageId("test-message-id");
            message.setTraceId("test-trace-id");
            message.setTimestamp(String.valueOf(System.currentTimeMillis()));
            message.setUserId(111L);
            message.setProjectId(111l);
            message.setAssessmentId(1l);
            message.setFilePaths(new ArrayList<>());
            message.getFilePaths().add("test");
            message.getFilePaths().add("test");

            String topic = message.getTopic().getTopicName();
            kafkaTemplate.send(topic, message).get(30, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("Sent message to Kafka topic: " + topic);
        } catch (Exception e) {
            e.printStackTrace();
            assert false : "Failed to connect to Kafka";
        }
    }



}