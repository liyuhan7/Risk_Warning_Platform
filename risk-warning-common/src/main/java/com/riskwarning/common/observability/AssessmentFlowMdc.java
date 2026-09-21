package com.riskwarning.common.observability;

import org.slf4j.MDC;

/**
 * Worker 执行期 MDC 作用域。close 时恢复进入前的值而非整体清空，
 * 保证线程池串行任务之间不串线、也不破坏外层已有上下文。
 */
public final class AssessmentFlowMdc {

    public static final String PROJECT_ID = "projectId";
    public static final String ASSESSMENT_ID = "assessmentId";
    public static final String ANALYSIS_RUN_ID = "analysisRunId";
    public static final String TASK_ID = "taskId";
    public static final String TRACE_ID = "traceId";
    public static final String MESSAGE_ID = "messageId";
    public static final String KIND = "kind";
    public static final String ATTEMPT = "attempt";

    private static final String[] KEYS = {
            PROJECT_ID, ASSESSMENT_ID, ANALYSIS_RUN_ID,
            TASK_ID, TRACE_ID, MESSAGE_ID, KIND, ATTEMPT
    };

    private AssessmentFlowMdc() {
    }

    /** 按可用身份打开作用域；null 字段不写入，close 恢复原值。 */
    public static Scope open(AssessmentFlowContext context) {
        return open(
                PROJECT_ID, text(context.getProjectId()),
                ASSESSMENT_ID, text(context.getAssessmentId()),
                ANALYSIS_RUN_ID, context.getAnalysisRunId(),
                TASK_ID, context.getTaskId(),
                TRACE_ID, context.getTraceId(),
                MESSAGE_ID, context.getMessageId(),
                KIND, context.getKind(),
                ATTEMPT, text(context.getAttempt()));
    }

    public static Scope open(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("AssessmentFlow MDC 键值必须成对");
        }
        String[] previous = new String[keyValuePairs.length / 2];
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            String key = keyValuePairs[index];
            String value = keyValuePairs[index + 1];
            previous[index / 2] = MDC.get(key);
            if (value != null && !value.trim().isEmpty()) {
                MDC.put(key, value.trim());
            }
        }
        return new Scope(keyValuePairs, previous);
    }

    /** 作用域退出时逐键恢复进入前的 MDC 值。 */
    public static final class Scope implements AutoCloseable {

        private final String[] keyValuePairs;
        private final String[] previous;

        private Scope(String[] keyValuePairs, String[] previous) {
            this.keyValuePairs = keyValuePairs;
            this.previous = previous;
        }

        @Override
        public void close() {
            for (int index = 0; index < keyValuePairs.length; index += 2) {
                String value = previous[index / 2];
                if (value == null) {
                    MDC.remove(keyValuePairs[index]);
                } else {
                    MDC.put(keyValuePairs[index], value);
                }
            }
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
