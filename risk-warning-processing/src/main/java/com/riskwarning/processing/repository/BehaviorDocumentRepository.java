package com.riskwarning.processing.repository;

import com.riskwarning.common.po.behavior.Behavior;

import java.util.List;

/** Structured Behavior 的 Elasticsearch 读写边界。 */
public interface BehaviorDocumentRepository {

    void writeAll(List<Behavior> behaviors);

    /**
     * 按项目、评估、运行三作用域读取行为，供页面证据回溯与指标计算共用。
     *
     * 查询条件由 {@link BehaviorScopeQuery} 唯一构建，不允许退化为项目级读取；
     * 旧文档缺少评估或运行字段时天然不命中，不回退、不补默认值。
     */
    List<Behavior> findByScope(Long projectId, Long assessmentId, String analysisRunId);

    /**
     * 删除某运行下指定源文件已落库的行为文档。
     *
     * 同 analysisRunId 重跑（消息重投、崩溃恢复）进入抽取前先清理旧产物，
     * 规避真实模型输出不稳定导致稳定 ID 漂移后旧文档残留累加（p1-06 D5）。
     */
    void deleteByAnalysisRunIdAndSourceDocumentId(String analysisRunId, Long sourceDocumentId);
}
