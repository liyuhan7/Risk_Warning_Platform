package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.dto.fact.FactExtractionResult;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 编排 Evidence 读取、事实抽取、分类向量化和 Behavior 写入。 */
@Service
public class FactExtractionPipeline {

    private final EvidenceExtractionService evidenceExtractionService;
    private final FactExtractionService factExtractionService;
    private final StructuredBehaviorWriter behaviorWriter;
    private final BehaviorDocumentRepository behaviorDocumentRepository;

    public FactExtractionPipeline(EvidenceExtractionService evidenceExtractionService,
                                  FactExtractionService factExtractionService,
                                  StructuredBehaviorWriter behaviorWriter,
                                  BehaviorDocumentRepository behaviorDocumentRepository) {
        this.evidenceExtractionService = evidenceExtractionService;
        this.factExtractionService = factExtractionService;
        this.behaviorWriter = behaviorWriter;
        this.behaviorDocumentRepository = behaviorDocumentRepository;
    }

    /**
     * 所有文件抽取成功后一次写入，抽取失败时不会写入部分 Behavior。
     * 每个文档抽取前先清理该 run 下该文档已落库的旧产物（p1-06 D5），
     * 保证同 analysisRunId 重跑（消息重投、崩溃恢复）不会因模型输出漂移残留重复文档。
     */
    public List<Behavior> process(AnalysisScope scope, List<SourceDocumentRef> documents) {
        if (scope == null || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("事实抽取链需要运行作用域和源文件");
        }
        List<Behavior> behaviors = new ArrayList<>();
        for (SourceDocumentRef document : documents) {
            if (document == null || document.getSourceDocumentId() == null
                    || document.getSourceDocumentId() <= 0) {
                throw new IllegalArgumentException("源文件身份不合法");
            }
            behaviorDocumentRepository.deleteByAnalysisRunIdAndSourceDocumentId(
                    scope.getAnalysisRunId(), document.getSourceDocumentId());
            List<EvidenceChunk> evidenceChunks = evidenceExtractionService.findBySourceDocument(
                    scope, document.getSourceDocumentId());
            FactExtractionResult result = factExtractionService.extract(scope, evidenceChunks);
            if (result.getFailures() != null && !result.getFailures().isEmpty()) {
                throw new IllegalStateException("事实抽取返回失败项，禁止写入部分结果");
            }
            behaviors.addAll(result.getBehaviors());
        }
        behaviorWriter.enrichAndWrite(behaviors);
        return behaviors;
    }
}
