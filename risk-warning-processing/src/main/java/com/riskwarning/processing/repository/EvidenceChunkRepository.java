package com.riskwarning.processing.repository;

import com.riskwarning.common.po.evidence.EvidenceChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Evidence 的 PostgreSQL 权威读写入口。 */
@Repository
public interface EvidenceChunkRepository extends JpaRepository<EvidenceChunk, String> {

    List<EvidenceChunk> findBySourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(Long sourceDocumentId);

    Optional<EvidenceChunk> findByIdAndSourceDocumentIdAndProjectIdAndAssessmentId(
            String id, Long sourceDocumentId, Long projectId, Long assessmentId);

    /** 按评估作用域列出证据；页码为空的历史数据由数据库排序置于末尾。 */
    List<EvidenceChunk> findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
            Long projectId, Long assessmentId);

    List<EvidenceChunk> findByProjectIdAndAssessmentIdAndSourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(
            Long projectId, Long assessmentId, Long sourceDocumentId);

    /** 依据行为引用批量取回证据原文；归属复核由 EvidenceQueryService 完成。 */
    List<EvidenceChunk> findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(Collection<String> ids);
}
