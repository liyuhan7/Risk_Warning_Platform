package com.riskwarning.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AssessmentFlow 统一日志测试：固定字段顺序、占位符、单行净化与级别映射。
 */
class AssessmentFlowLoggerTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private AssessmentFlowLogger logger;

    @BeforeEach
    void setup() {
        logger = new AssessmentFlowLogger();
        ch.qos.logback.classic.Logger logback =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AssessmentFlowLogger.class);
        appender.start();
        logback.addAppender(appender);
    }

    @AfterEach
    void teardown() {
        ch.qos.logback.classic.Logger logback =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AssessmentFlowLogger.class);
        logback.detachAppender(appender);
    }

    @Test
    void formatsFixedFieldOrderWithPlaceholders() {
        AssessmentFlowEvent event = AssessmentFlowEvent.builder(
                        AssessmentFlowStage.DURABLE_DONE, AssessmentFlowStatus.SUCCEEDED)
                .taskId("work-1").kind("TEST").attempt(2).build();

        String line = logger.format(event);

        assertThat(line).isEqualTo("stage=DURABLE_DONE status=SUCCEEDED "
                + "projectId=- assessmentId=- analysisRunId=- taskId=work-1 kind=TEST "
                + "attempt=2 messageId=- traceId=- behaviorId=- elapsedMs=- ");
    }

    @Test
    void sanitizesLineBreaksAndTabs() {
        AssessmentFlowEvent event = AssessmentFlowEvent.builder(
                        AssessmentFlowStage.BEHAVIOR_INBOX, AssessmentFlowStatus.SUCCEEDED)
                .analysisRunId("run\n1").taskId("task\t1").traceId("trace\r1").build();

        String line = logger.format(event);

        assertThat(line).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        assertThat(line).contains("analysisRunId=run 1").contains("taskId=task 1");
    }

    @Test
    void rejectsNegativeElapsedAndMissingStage() {
        assertThrows(IllegalArgumentException.class, () -> AssessmentFlowEvent
                .builder(AssessmentFlowStage.RETRIEVAL_BATCH, AssessmentFlowStatus.SUCCEEDED)
                .elapsedMs(-1L).build());
        assertThrows(IllegalArgumentException.class, () -> AssessmentFlowEvent
                .builder(null, AssessmentFlowStatus.SUCCEEDED).build());
    }

    @Test
    void failedEventIsLoggedAtErrorLevelWithThrowable() {
        RuntimeException failure = new IllegalStateException("失败");
        AssessmentFlowEvent event = AssessmentFlowEvent.builder(
                        AssessmentFlowStage.DURABLE_FAILED, AssessmentFlowStatus.FAILED)
                .taskId("work-1").failure(failure).build();

        logger.error(event);

        ILoggingEvent logged = appender.list.get(0);
        assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
        assertThat(logged.getFormattedMessage()).contains("stage=DURABLE_FAILED");
        assertThat(logged.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void retryEventIsLoggedAtWarnLevel() {
        AssessmentFlowEvent event = AssessmentFlowEvent.builder(
                        AssessmentFlowStage.DURABLE_RETRY, AssessmentFlowStatus.RETRY)
                .taskId("work-1").build();

        logger.warn(event);

        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void normalEventIsLoggedAtInfoLevel() {
        AssessmentFlowEvent event = AssessmentFlowEvent.builder(
                        AssessmentFlowStage.P2_COMPLETE, AssessmentFlowStatus.SUCCEEDED)
                .analysisRunId("run-1").elapsedMs(12L).build();

        logger.info(event);

        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }
}
