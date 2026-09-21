package com.riskwarning.common.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AssessmentFlow MDC 作用域测试：恢复语义、嵌套与异常路径。
 */
class AssessmentFlowMdcTest {

    @Test
    void writesAvailableIdentityFieldsAndSkipsNull() {
        AssessmentFlowContext context = AssessmentFlowContext.builder()
                .taskId("work-1").kind("TEST").attempt(2).build();

        try (AssessmentFlowMdc.Scope ignored = AssessmentFlowMdc.open(context)) {
            assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isEqualTo("work-1");
            assertThat(MDC.get(AssessmentFlowMdc.KIND)).isEqualTo("TEST");
            assertThat(MDC.get(AssessmentFlowMdc.ATTEMPT)).isEqualTo("2");
            assertThat(MDC.get(AssessmentFlowMdc.ANALYSIS_RUN_ID)).isNull();
        }

        assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isNull();
    }

    @Test
    void closeRestoresOuterValuesInsteadOfClearing() {
        MDC.put(AssessmentFlowMdc.TASK_ID, "outer");
        try {
            try (AssessmentFlowMdc.Scope ignored = AssessmentFlowMdc.open(
                    AssessmentFlowContext.builder().taskId("inner").build())) {
                assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isEqualTo("inner");
            }
            assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isEqualTo("outer");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void nestedScopesRestoreCorrectly() {
        try (AssessmentFlowMdc.Scope outer = AssessmentFlowMdc.open(
                AssessmentFlowContext.builder().taskId("outer").build())) {
            try (AssessmentFlowMdc.Scope inner = AssessmentFlowMdc.open(
                    AssessmentFlowContext.builder().taskId("inner").build())) {
                assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isEqualTo("inner");
            }
            assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isEqualTo("outer");
        }
        assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isNull();
    }

    @Test
    void scopeIsRestoredEvenWhenBodyThrows() {
        try {
            try (AssessmentFlowMdc.Scope ignored = AssessmentFlowMdc.open(
                    AssessmentFlowContext.builder().taskId("work-1").build())) {
                throw new IllegalStateException("任务失败");
            }
        } catch (IllegalStateException expected) {
            // 关闭语义与异常路径无关
        }
        assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isNull();
    }

    @Test
    void keyValuesMustBePaired() {
        assertThrows(IllegalArgumentException.class,
                () -> AssessmentFlowMdc.open(AssessmentFlowMdc.TASK_ID));
    }

    @Test
    void whitespaceOnlyValueIsNotWritten() {
        try (AssessmentFlowMdc.Scope ignored = AssessmentFlowMdc.open(
                AssessmentFlowMdc.TASK_ID, "  ",
                AssessmentFlowMdc.KIND, "TEST")) {
            assertThat(MDC.get(AssessmentFlowMdc.TASK_ID)).isNull();
            assertThat(MDC.get(AssessmentFlowMdc.KIND)).isEqualTo("TEST");
        }
    }
}
