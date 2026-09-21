package com.riskwarning.report.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.report.service.AnalysisRunCompletionService;
import com.riskwarning.report.service.AnalysisRunFailureService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 可靠/兼容消费切换测试：两个 Kafka listener 不得同时存在，
 * 默认配置保持兼容消费者，可靠开关打开后切换为入箱 listener。
 */
class DurableReportListenerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DurableWorkStore.class, () -> mock(DurableWorkStore.class))
            .withBean(ReportMessageCodec.class)
            .withBean(AnalysisRunCompletionService.class,
                    () -> mock(AnalysisRunCompletionService.class))
            .withBean(AnalysisRunFailureService.class,
                    () -> mock(AnalysisRunFailureService.class))
            .withUserConfiguration(DurableReportMessageTask.class, MessageTask.class);

    @Test
    void reliabilityEnabledReplacesCompatListenerWithInboxListener() {
        runner.withPropertyValues("assessment.reliability.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(DurableReportMessageTask.class);
            assertThat(context).doesNotHaveBean(MessageTask.class);
        });
    }

    @Test
    void defaultConfigurationKeepsCompatListenerOnly() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MessageTask.class);
            assertThat(context).doesNotHaveBean(DurableReportMessageTask.class);
        });
    }

    @Test
    void explicitFalseConfigurationKeepsCompatListenerOnly() {
        runner.withPropertyValues("assessment.reliability.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(MessageTask.class);
            assertThat(context).doesNotHaveBean(DurableReportMessageTask.class);
        });
    }
}
