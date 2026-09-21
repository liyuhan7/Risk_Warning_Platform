package com.riskwarning.common.reliability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityModeReporterTest {

    @Test
    void reportsCompatibleModeWhenPropertyIsMissing() {
        assertMode(Collections.emptyMap(), Level.WARN, "COMPATIBLE");
    }

    @Test
    void reportsEnabledModeWhenConfigured() {
        assertMode(Collections.singletonMap("assessment.reliability.enabled", "true"),
                Level.INFO, "ENABLED");
    }

    @Test
    void reportsCompatibleModeWhenExplicitlyDisabled() {
        assertMode(Collections.singletonMap("assessment.reliability.enabled", "false"),
                Level.WARN, "COMPATIBLE");
    }

    @Test
    void rejectsNonBooleanEnabledValue() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test",
                        Collections.singletonMap("assessment.reliability.enabled", "1")));
        try {
            ReliabilityModeReporter reporter = new ReliabilityModeReporter(context.getEnvironment());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> reporter.onApplicationEvent(
                            new org.springframework.context.event.ContextRefreshedEvent(context)));
        } finally {
            context.close();
        }
    }

    private void assertMode(java.util.Map<String, Object> properties,
                            Level expectedLevel, String expectedMode) {
        GenericApplicationContext context = new GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", properties));
        context.setId("test-service");
        Logger logger = (Logger) LoggerFactory.getLogger(ReliabilityModeReporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ReliabilityModeReporter reporter = new ReliabilityModeReporter(context.getEnvironment());
            reporter.onApplicationEvent(new org.springframework.context.event.ContextRefreshedEvent(context));
            reporter.onApplicationEvent(new org.springframework.context.event.ContextRefreshedEvent(context));

            assertEquals(1, appender.list.size(), "同一上下文只应报告一次模式");
            assertEquals(expectedLevel, appender.list.get(0).getLevel());
            assertTrue(appender.list.get(0).getFormattedMessage().contains(expectedMode));
        } finally {
            logger.detachAppender(appender);
            context.close();
        }
    }
}
