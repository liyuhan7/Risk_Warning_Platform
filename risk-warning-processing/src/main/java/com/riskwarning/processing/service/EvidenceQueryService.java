package com.riskwarning.processing.service;

import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.processing.repository.EvidenceChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 证据只读查询。
 *
 * 与写入侧 {@link EvidenceExtractionService} 分离：本服务只做作用域过滤与完整性复核，
 * 不触发解析或持久化，避免页面读取反向影响抽取主链。
 *
 * 查询一律携带项目与评估两个身份，不使用只按项目或只按文件的退路，
 * 否则同项目不同评估的证据会互相混入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidenceQueryService {

    /** 单次批量取证据的上限，防止一次请求拉取整库。 */
    static final int MAX_BATCH_IDS = 500;

    private final EvidenceChunkRepository evidenceChunkRepository;

    /**
     * 按评估作用域列出证据，可选收窄到单个来源文件。
     *
     * @param sourceDocumentId 为 null 时返回该评估全部文件的分段
     */
    public List<EvidenceChunk> listByScope(Long projectId, Long assessmentId, Long sourceDocumentId) {
        validateScope(projectId, assessmentId);
        if (sourceDocumentId != null && sourceDocumentId <= 0) {
            throw new IllegalArgumentException("sourceDocumentId 必须为正数");
        }

        List<EvidenceChunk> chunks = sourceDocumentId == null
                ? evidenceChunkRepository
                        .findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                                projectId, assessmentId)
                : evidenceChunkRepository
                        .findByProjectIdAndAssessmentIdAndSourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(
                                projectId, assessmentId, sourceDocumentId);

        return verifyAll(chunks, projectId, assessmentId);
    }

    /**
     * 依据行为引用的证据 ID 批量取回原文。
     *
     * 调用方给出的 ID 不受信任，因此仍要求项目与评估身份；
     * 任一命中记录的归属与作用域不符即整批拒绝，避免越权读取他人评估的证据。
     * 引用但已不存在的 ID 只记告警，交由页面显示“证据引用缺失”，不作为错误。
     */
    public List<EvidenceChunk> listByIds(List<String> ids, Long projectId, Long assessmentId) {
        validateScope(projectId, assessmentId);
        List<String> distinctIds = distinctIds(ids);

        List<EvidenceChunk> found = evidenceChunkRepository
                .findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(distinctIds);
        List<EvidenceChunk> verified = verifyAll(found, projectId, assessmentId);

        if (verified.size() < distinctIds.size()) {
            log.warn("[Evidence Query] 部分引用 ID 未命中: projectId={}, assessmentId={}, 请求 {} 条, 命中 {} 条",
                    projectId, assessmentId, distinctIds.size(), verified.size());
        }
        return verified;
    }

    private List<String> distinctIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        List<String> distinctIds = ids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (distinctIds.isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        if (distinctIds.size() > MAX_BATCH_IDS) {
            throw new IllegalArgumentException("单次最多查询 " + MAX_BATCH_IDS + " 条证据");
        }
        return distinctIds;
    }

    private List<EvidenceChunk> verifyAll(List<EvidenceChunk> chunks, Long projectId, Long assessmentId) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }
        List<EvidenceChunk> verified = new ArrayList<>(chunks.size());
        for (EvidenceChunk chunk : chunks) {
            if (!projectId.equals(chunk.getProjectId()) || !assessmentId.equals(chunk.getAssessmentId())) {
                throw new IllegalStateException("证据归属与查询作用域不一致: " + chunk.getId());
            }
            chunk.verifyIntegrity();
            verified.add(chunk);
        }
        return verified;
    }

    private void validateScope(Long projectId, Long assessmentId) {
        if (projectId == null || projectId <= 0 || assessmentId == null || assessmentId <= 0) {
            throw new IllegalArgumentException("projectId 与 assessmentId 必须为正数");
        }
    }
}
