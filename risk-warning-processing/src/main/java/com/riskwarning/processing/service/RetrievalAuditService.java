package com.riskwarning.processing.service;

import com.riskwarning.common.po.analysis.RetrievalAudit;
import com.riskwarning.processing.repository.RetrievalAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** 审计使用独立事务，业务运行回滚时仍保留诊断记录。 */
@Service
public class RetrievalAuditService {
    private final RetrievalAuditRepository repository;

    public RetrievalAuditService(RetrievalAuditRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetrievalAudit save(RetrievalAudit audit) {
        RetrievalAudit target = repository.findByAnalysisRunIdAndBehaviorId(
                audit.getAnalysisRunId(), audit.getBehaviorId()).orElse(audit);
        if (target.getId() != null) {
            audit.setId(target.getId());
            audit.setCreatedAt(target.getCreatedAt());
            if ((audit.getCandidates() == null || audit.getCandidates().isEmpty())
                    && target.getCandidates() != null && !target.getCandidates().isEmpty()) {
                audit.setCandidates(target.getCandidates());
            }
            if (audit.getEmbeddingModel() == null) { audit.setEmbeddingModel(target.getEmbeddingModel()); }
            if (audit.getEmbeddingVersion() == null) { audit.setEmbeddingVersion(target.getEmbeddingVersion()); }
        }
        if (audit.getCreatedAt() == null) { audit.setCreatedAt(LocalDateTime.now()); }
        audit.setUpdatedAt(LocalDateTime.now());
        return repository.saveAndFlush(audit);
    }
}
