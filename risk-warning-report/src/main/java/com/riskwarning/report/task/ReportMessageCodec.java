package com.riskwarning.report.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.KafkaTopic;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import org.springframework.stereotype.Component;

/**
 * Report Durable Inbox 载荷编解码。listener 收到评估完成事件后立即完成
 * 身份与作用域校验，并把完整消息快照写入 Durable Work，执行阶段不回读 Kafka。
 * payload 直接保存消息对象 JSON，kind 已确定具体消息类型，无需信封包装。
 */
@Component
public class ReportMessageCodec {

    /** task_key 数据库上限 160 字符，与 Outbox/Inbox 幂等键规则一致。 */
    private static final int TASK_KEY_LIMIT = 160;

    private final ObjectMapper mapper;

    public ReportMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验消息身份与作用域后编码载荷；messageId 即 Inbox 幂等 taskKey。 */
    public String encode(AssessmentCompletedEventMessage message) {
        requireIdentity(message);
        return writeValue(message);
    }

    /** 解码评估完成事件并复核结构，损坏载荷进入 Durable Work 重试。 */
    public AssessmentCompletedEventMessage decode(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalStateException("Report 任务载荷不能为空");
        }
        AssessmentCompletedEventMessage message;
        try {
            message = mapper.readValue(payload, AssessmentCompletedEventMessage.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Report 任务载荷反序列化失败", exception);
        }
        requireIdentity(message);
        return message;
    }

    /**
     * messageId 即幂等 taskKey，必须非空且不超过数据库列宽；
     * topic 必须保持评估完成事件契约，作用域复用 AnalysisScope 构造校验。
     */
    private void requireIdentity(AssessmentCompletedEventMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("Report 任务消息不能为空");
        }
        if (message.getTopic() != KafkaTopic.ASSESSMENT_COMPLETED_EVENTS) {
            throw new IllegalArgumentException("Report 任务消息 topic 不符");
        }
        if (message.getMessageId() == null || message.getMessageId().trim().isEmpty()) {
            throw new IllegalArgumentException("Report 任务消息缺少 messageId");
        }
        if (message.getMessageId().length() > TASK_KEY_LIMIT) {
            throw new IllegalArgumentException("Report 任务消息 messageId 超过 task_key 长度上限");
        }
        if (message.getUserId() == null || message.getUserId() <= 0) {
            throw new IllegalArgumentException("Report 任务消息缺少有效 userId");
        }
        requireScope(message);
    }

    /** 作用域身份由 AnalysisScope 构造器统一强制完整。 */
    private AnalysisScope requireScope(AssessmentCompletedEventMessage message) {
        return new AnalysisScope(
                message.getProjectId(), message.getAssessmentId(), message.getAnalysisRunId());
    }

    private String writeValue(AssessmentCompletedEventMessage message) {
        try {
            return mapper.writeValueAsString(message);
        } catch (Exception exception) {
            throw new IllegalStateException("Report 任务载荷序列化失败", exception);
        }
    }
}
