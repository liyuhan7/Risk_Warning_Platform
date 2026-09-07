package com.riskwarning.processing.repository;

import com.riskwarning.common.po.evidence.EvidenceChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Evidence 的 PostgreSQL 权威读写入口。 */
@Repository
public interface EvidenceChunkRepository extends JpaRepository<EvidenceChunk, String> {

    List<EvidenceChunk> findBySourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(Long sourceDocumentId);

    Optional<EvidenceChunk> findByIdAndSourceDocumentIdAndProjectIdAndAssessmentId(
            String id, Long sourceDocumentId, Long projectId, Long assessmentId);
}
