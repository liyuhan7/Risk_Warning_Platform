package com.riskwarning.common.message;

import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.enums.KafkaTopic;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import lombok.*;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class BehaviorProcessingTaskMessage extends Message {

    private DataSourceTypeEnum type;
    private List<String> filePaths;
    private List<SourceDocumentRef> documents;


    public BehaviorProcessingTaskMessage() {
        this.setTopic(KafkaTopic.BEHAVIOR_PROCESSING_TASKS);
    }

    public BehaviorProcessingTaskMessage(String messageId, String timestamp, String traceId, Long userId, Long projectId, Long assessmentId, DataSourceTypeEnum type, List<String> filePaths) {
        this(messageId, timestamp, traceId, userId, projectId, assessmentId, null, type, filePaths);
    }

    public BehaviorProcessingTaskMessage(String messageId, String timestamp, String traceId, Long userId,
                                         Long projectId, Long assessmentId, String analysisRunId,
                                         DataSourceTypeEnum type, List<String> filePaths) {
        super(messageId, timestamp, traceId, userId, projectId, assessmentId, analysisRunId);
        this.setTopic(KafkaTopic.BEHAVIOR_PROCESSING_TASKS);
        this.type = type;
        if(this.type == null) {
            throw new IllegalArgumentException("Invalid data source type: " + type);
        }
        this.filePaths = filePaths;
    }

    public static BehaviorProcessingTaskMessage forDocuments(
            String messageId, String timestamp, String traceId, Long userId,
            Long projectId, Long assessmentId, String analysisRunId,
            DataSourceTypeEnum type, List<SourceDocumentRef> documents) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Scoped documents must not be empty");
        }
        for (SourceDocumentRef document : documents) {
            if (document == null || document.getSourceDocumentId() == null
                    || document.getSourceDocumentId() <= 0 || document.getFilePath() == null
                    || document.getFilePath().trim().isEmpty()) {
                throw new IllegalArgumentException("Invalid scoped document");
            }
        }
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                messageId, timestamp, traceId, userId, projectId, assessmentId,
                analysisRunId, type, documents == null ? null : documents.stream()
                    .map(SourceDocumentRef::getFilePath)
                    .collect(java.util.stream.Collectors.toList()));
        message.documents = new java.util.ArrayList<>(documents);
        return message;
    }

}


