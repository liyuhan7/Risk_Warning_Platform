package com.riskwarning.common;


import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.riskwarning.common.config.TestConsumer;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.indicator.IndicatorRiskStatus;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.regulation.Regulation;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.org.OrgApplication;
import com.riskwarning.common.enums.KafkaTopic;
import com.riskwarning.common.message.*;
import com.riskwarning.common.po.indicator.Indicator;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.repository.AssessmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


@SpringBootTest(classes = OrgApplication.class)
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
    private TestConsumer testConsumer;

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

            Assessment assessment1 = assessmentRepository.findById(88L);

            // 外键约束：project_id 须为已存在的 t_project.id
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
            Assessment saved = assessmentRepository.saveAndFlush(assessment);

            // projectId=1 存在历史行，findByProjectId 不唯一，改按主键回读
            Assessment reloaded = assessmentRepository.findById(saved.getId());
            assert reloaded != null && reloaded.getOverallScore() == 0.0;
            assert "LOW_RISK".equals(String.valueOf(reloaded.getOverallRiskLevel()));
        } catch (Exception e) {
            e.printStackTrace();
            assert false : "Failed to query assessments from the database";
        }
    }

}