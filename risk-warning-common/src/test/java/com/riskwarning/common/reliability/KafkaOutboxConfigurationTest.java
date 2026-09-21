package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.utils.KafkaUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaOutboxConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(KafkaOutboxConfiguration.class)
            // KafkaUtils 的模板字段需要真实注入，这里注入 mock 的 KafkaTemplate 供其装配
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(KafkaUtils.class, KafkaUtils::new)
            .withBean(DurableWorkStore.class, () -> mock(DurableWorkStore.class))
            .withBean(AssessmentFlowLogger.class, AssessmentFlowLogger::new)
            .withBean(TransactionTemplate.class,
                    () -> new TransactionTemplate(mock(PlatformTransactionManager.class)));

    @Test
    void keepsCompatibleDeliveryWhenReliabilityDisabled() {
        context.run(result -> {
            assertThat(result).doesNotHaveBean(KafkaOutboxCodec.class);
            assertThat(result).getBean(KafkaOutbox.class).isInstanceOf(AfterCommitKafkaOutbox.class);
            assertThat(result).doesNotHaveBean(KafkaOutboxHandler.class);
        });
    }

    @Test
    void missingReliabilityPropertyDefaultsToCompatibleDelivery() {
        // matchIfMissing 生效：无属性时也装配兼容实现
        context.run(result -> assertThat(result)
                .getBean(KafkaOutbox.class).isInstanceOf(AfterCommitKafkaOutbox.class));
    }

    @Test
    void enablesTransactionalOutboxWhenReliabilityEnabled() {
        context.withPropertyValues("assessment.reliability.enabled=true")
                .run(result -> {
                    assertThat(result).hasSingleBean(KafkaOutbox.class);
                    assertThat(result).getBean(KafkaOutbox.class)
                            .isInstanceOf(TransactionalKafkaOutbox.class);
                    assertThat(result).hasSingleBean(KafkaOutboxHandler.class);
                });
    }
}
