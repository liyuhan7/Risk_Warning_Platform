package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowLogger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReliabilityConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ReliabilityConfiguration.class)
            .withBean(JdbcTemplate.class, ReliabilityConfigurationTest::migratedJdbc)
            .withBean(TransactionTemplate.class,
                    () -> new TransactionTemplate(mock(PlatformTransactionManager.class)))
            .withBean(AssessmentFlowLogger.class, AssessmentFlowLogger::new);

    @Test
    void staysDisabledUntilMigrationAndServiceOptIn() {
        context.run(result -> {
            assertThat(result).doesNotHaveBean(DurableWorkStore.class);
            assertThat(result).doesNotHaveBean(DurableWorker.class);
        });
    }

    @Test
    void createsInfrastructureWhenExplicitlyEnabled() {
        context.withPropertyValues(
                        "assessment.reliability.enabled=true",
                        "assessment.reliability.namespace=test-service",
                        "assessment.reliability.worker-threads=1")
                .run(result -> {
                    assertThat(result).hasSingleBean(DurableWorkStore.class);
                    assertThat(result).hasSingleBean(DurableWorker.class);
                });
    }

    @Test
    void refusesToStartWhenSchemaIsIncomplete() {
        JdbcTemplate missingSchema = mock(JdbcTemplate.class);
        when(missingSchema.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        new ApplicationContextRunner()
                .withUserConfiguration(ReliabilityConfiguration.class)
                .withBean(JdbcTemplate.class, () -> missingSchema)
                .withBean(TransactionTemplate.class,
                        () -> new TransactionTemplate(mock(PlatformTransactionManager.class)))
                .withBean(AssessmentFlowLogger.class, AssessmentFlowLogger::new)
                .withPropertyValues(
                        "assessment.reliability.enabled=true",
                        "assessment.reliability.namespace=test-service")
                .run(result -> {
                    assertThat(result).hasFailed();
                    Throwable root = rootCause(result.getStartupFailure());
                    assertThat(root)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("t_durable_work 核心列")
                            .hasMessageContaining("documents/schema.sql");
                });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static JdbcTemplate migratedJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(13, 1, 1, 1, 1, 1, 1);
        return jdbc;
    }

    @Test
    void rejectsHeartbeatLongerThanLease() {
        context.withPropertyValues(
                        "assessment.reliability.enabled=true",
                        "assessment.reliability.namespace=test-service",
                        "assessment.reliability.lease-duration=10s",
                        "assessment.reliability.heartbeat-interval=10s")
                .run(result -> assertThat(result).hasFailed());
    }
}
