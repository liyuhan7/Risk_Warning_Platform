package com.riskwarning.common;


import cn.hutool.core.lang.Assert;
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
import com.riskwarning.common.message.*;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.report.ReportApplication;
import com.riskwarning.report.repository.AssessmentRepository;
import com.riskwarning.report.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import javax.sql.DataSource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@SpringBootTest(classes = ReportApplication.class)
public class ConnectivityTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ElasticsearchClient client;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private ReportService reportService;


    @Autowired
    private KafkaTemplate<String, Message> kafkaTemplate;

//    @Autowired
//    private IndicatorResultRepository indicatorResultRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;

//    @Test
//    public void testDatabaseConnection() {
//        try {
//            // 尝试获取PostgreSQL连接以验证连接
//
//            Assessment assessment = assessmentRepository.findById(88L).get();
//
//            IndicatorResult ir = IndicatorResult.builder()
//                    .projectId(22L)
//                    .assessmentId(1000L)
//                    .indicatorEsId("indicatorEsId")
//                    .indicatorName("test")
//                    .indicatorLevel(0)
//                    .dimension("test")
//                    .type("test")
//                    .calculatedScore(0.0)
//                    .maxPossibleScore(0.0)
//                    .usedCalculationRuleType("auto")
//                    .calculationDetails(null)
//                    .riskTriggered(false)
//                    .riskStatus(IndicatorRiskStatus.fromCode("NOT_EVALUATED"))
//                    .calculatedAt(LocalDateTime.now())
//                    .createdAt(LocalDateTime.now())
//                    .build();
//            IndicatorResult indicatorResult = indicatorResultRepository.save(ir);
//            System.out
//                    .println("Connected to the database and saved IndicatorResult with ID: " + indicatorResult.getId());
//        } catch (Exception e) {
//            e.printStackTrace();
//            assert false : "Failed to connect to the database";
//        }
//    }

    @Test
    public void testAssessmentQuery() {
        try {


            Assessment assessment = Assessment.builder()
                    .projectId(1L)
                    .assessmentDate(LocalDateTime.now())
                    .overallScore(0.0)
                    .overallRiskLevel(RiskLevelEnum.LOW_RISK)
                    // details 列为 jsonb，空串不是合法 JSON，写入合法对象字面量
                    .details("{}")
                    .recommendations("")
                    .status(AssessmentStatusEnum.ASSESSING)
                    .createdAt(LocalDateTime.now())
                    .build();
            assessmentRepository.saveAndFlush(assessment);
        } catch (Exception e) {
            e.printStackTrace();
            assert false : "Failed to query assessments from the database";
        }
    }


//    @Test
//    public void testRiskLevelQuery() {
//        try {
//            System.out.println(reportService.assembleRisk(144L,"企业信用风险","MEDIUM_RISK").toString());
//        }catch (Exception e){
//            e.printStackTrace();
//        }
//    }


    //测试es连通性
    @Test
    public void testesConnect() throws IOException {
        SearchResponse<Behavior> resp = client.search(s -> s
                        .index(ElasticSearchConfig.BEHAVIOR_INDEX)
                        .size(10)
                        .query(q -> q.bool(ma -> ma.must(m1 ->m1.term(t->t.field("projectId").value(2))))),
                Behavior.class
        );

        List<Behavior> allBehaviors = new ArrayList<>();
        if (resp != null && resp.hits() != null && resp.hits().hits() != null) {
            for (Hit<Behavior> hit : resp.hits().hits()) {
                Behavior behavior = hit.source();
                if (behavior != null) {
                    allBehaviors.add(behavior);
                }
            }
        }
        Assert.isTrue(!allBehaviors.isEmpty(), "t_behavior 应有 projectId=2 的数据");
    }

    /**
     * 诊断测试1：用 ObjectNode 查看 ES 中 t_risk 的原始 JSON，确认 risk_level 的实际存储值
     */
    @Test
    public void testDiagnoseRiskLevelRawValue() throws IOException {
        SearchResponse<ObjectNode> resp = client.search(s -> s
                        .index(ElasticSearchConfig.RISK_INDEX)
                        .size(5),
                ObjectNode.class
        );
        if (resp.hits() != null && resp.hits().hits() != null) {
            for (Hit<ObjectNode> hit : resp.hits().hits()) {
                ObjectNode node = hit.source();
                if (node != null) {
                    System.out.println("=== Raw risk document ===");
                    System.out.println("risk_level raw value: [" + node.get("risk_level") + "]");
                    System.out.println("status raw value: [" + node.get("status") + "]");
                    System.out.println("Full JSON: " + node.toString());
                }
            }
        } else {
            System.out.println("No risk documents found in t_risk index");
        }
    }

    /**
     * 诊断测试2：用 Risk.class 反序列化，验证 riskLevel 枚举是否能正确映射
     */
    @Test
    public void testDiagnoseRiskDeserialization() throws IOException {
        SearchResponse<Risk> resp = client.search(s -> s
                        .index(ElasticSearchConfig.RISK_INDEX)
                        .size(5),
                Risk.class
        );
        if (resp.hits() != null && resp.hits().hits() != null) {
            for (Hit<Risk> hit : resp.hits().hits()) {
                Risk risk = hit.source();
                if (risk != null) {
                    System.out.println("=== Deserialized Risk ===");
                    System.out.println("riskLevel: " + risk.getRiskLevel());
                    System.out.println("status: " + risk.getStatus());
                    System.out.println("name: " + risk.getName());
                    System.out.println("dimension: " + risk.getDimension());
                    System.out.println("projectId: " + risk.getProjectId());
                    System.out.println("assessmentId: " + risk.getAssessmentId());
                    if (risk.getRiskLevel() == null) {
                        System.err.println("!!! riskLevel is NULL - deserialization failed !!!");
                    }
                }
            }
        } else {
            System.out.println("No risk documents found in t_risk index");
        }
    }


}