package com.riskwarning.common.message;

import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.KafkaTopic;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class IndicatorCalculationTaskMessage extends Message{
    /** P2 demo 用于重新校验文件哈希；旧消息允许为空。 */
    private List<SourceDocumentRef> documents;

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

    public IndicatorCalculationTaskMessage withDocuments(List<SourceDocumentRef> documents) {
        this.documents = documents == null ? null : new java.util.ArrayList<>(documents);
        return this;
    }

}
