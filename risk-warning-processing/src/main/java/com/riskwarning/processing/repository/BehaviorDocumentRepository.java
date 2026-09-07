package com.riskwarning.processing.repository;

import com.riskwarning.common.po.behavior.Behavior;

import java.util.List;

/** Structured Behavior 的 Elasticsearch 写入与清理边界。 */
public interface BehaviorDocumentRepository {

    void writeAll(List<Behavior> behaviors);

    /**
     * 删除某运行下指定源文件已落库的行为文档。
     *
     * 同 analysisRunId 重跑（消息重投、崩溃恢复）进入抽取前先清理旧产物，
     * 规避真实模型输出不稳定导致稳定 ID 漂移后旧文档残留累加（p1-06 D5）。
     */
    void deleteByAnalysisRunIdAndSourceDocumentId(String analysisRunId, Long sourceDocumentId);
}
