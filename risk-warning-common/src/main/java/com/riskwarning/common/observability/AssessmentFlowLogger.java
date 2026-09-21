package com.riskwarning.common.observability;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AssessmentFlow 统一结构化日志。固定前缀与固定字段顺序，
 * 供日志聚合按 stage/status/analysisRunId 等身份检索。
 * 日志动作自身不抛业务异常，不改变调用方执行结果。
 */
@Slf4j
@Component
public class AssessmentFlowLogger {

    private static final String PREFIX = "[AssessmentFlow]";
    private static final String MISSING = "-";

    private final Logger logger;

    public AssessmentFlowLogger() {
        this(LoggerFactory.getLogger(AssessmentFlowLogger.class));
    }

    AssessmentFlowLogger(Logger logger) {
        this.logger = logger;
    }

    /** 正常路径事件。 */
    public void info(AssessmentFlowEvent event) {
        logAt(event, false);
    }

    /** 重试等可恢复事件。 */
    public void warn(AssessmentFlowEvent event) {
        logAt(event, true);
    }

    /** 失败事件。 */
    public void error(AssessmentFlowEvent event) {
        logAt(event, true);
    }

    private void logAt(AssessmentFlowEvent event, boolean isWarnOrError) {
        if (event == null) { return; }
        String line = PREFIX + " " + format(event);
        if (event.getStatus() == AssessmentFlowStatus.FAILED) {
            if (event.getFailure() != null) { logger.error(line, event.getFailure()); }
            else { logger.error(line); }
            return;
        }
        if (isWarnOrError) {
            if (event.getFailure() != null) { logger.warn(line, event.getFailure()); }
            else { logger.warn(line); }
            return;
        }
        if (event.getFailure() != null) { logger.info(line, event.getFailure()); }
        else { logger.info(line); }
    }

    /** 字段顺序固定；缺失字段输出占位符，空白字符被压缩保证单行可解析。 */
    String format(AssessmentFlowEvent event) {
        StringBuilder line = new StringBuilder();
        append(line, "stage", event.getStage() == null ? null : event.getStage().name());
        append(line, "status", event.getStatus() == null ? null : event.getStatus().name());
        append(line, "projectId", text(event.getProjectId()));
        append(line, "assessmentId", text(event.getAssessmentId()));
        append(line, "analysisRunId", event.getAnalysisRunId());
        append(line, "taskId", event.getTaskId());
        append(line, "kind", event.getKind());
        append(line, "attempt", text(event.getAttempt()));
        append(line, "messageId", event.getMessageId());
        append(line, "traceId", event.getTraceId());
        append(line, "behaviorId", event.getBehaviorId());
        append(line, "elapsedMs", text(event.getElapsedMs()));
        return line.toString();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void append(StringBuilder line, String name, String value) {
        line.append(name).append('=')
                .append(value == null || value.trim().isEmpty() ? MISSING : sanitize(value))
                .append(' ');
    }

    /** 换行、回车与制表符替换为空格，避免结构化日志被伪造行拆分。 */
    private static String sanitize(String value) {
        String trimmed = value.trim();
        if (trimmed.indexOf('\n') < 0 && trimmed.indexOf('\r') < 0 && trimmed.indexOf('\t') < 0) {
            return trimmed;
        }
        return trimmed.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
