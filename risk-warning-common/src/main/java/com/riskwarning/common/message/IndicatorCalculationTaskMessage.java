package com.riskwarning.common.message;

import com.riskwarning.common.enums.KafkaTopic;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

@EqualsAndHashCode(callSuper = true)
@Data
public class IndicatorCalculationTaskMessage extends Message{

    public IndicatorCalculationTaskMessage(String messageId, String timestamp, String traceId, Long userId, Long projectId, Long assessmentId) {
        this(messageId, timestamp, traceId, userId, projectId, assessmentId, null);
    }

    public IndicatorCalculationTaskMessage(String messageId, String timestamp, String traceId, Long userId,
                                           Long projectId, Long assessmentId, String analysisRunId) {
        super(messageId, timestamp, traceId, userId, projectId, assessmentId, analysisRunId);
        this.setTopic(KafkaTopic.INDICATOR_CALCULATION_TASKS);
    }

    public IndicatorCalculationTaskMessage() {
        this.setTopic(KafkaTopic.INDICATOR_CALCULATION_TASKS);
    }

}
