package com.riskwarning.processing.service;

import com.riskwarning.common.dto.retrieval.RetrievalCandidateSnapshot;
import com.riskwarning.processing.dto.analysis.RetrievalCandidateVO;

/**
 * 候选快照到只读展示契约的映射，概览与后续分析上下文共用同一份判断。
 * 候选元数据可用与正文可用分开判断：指标可以没有正文，但候选 ID 和名称必须齐全。
 */
final class RetrievalCandidateVoMapper {

    private RetrievalCandidateVoMapper() { }

    static RetrievalCandidateVO toVo(RetrievalCandidateSnapshot snapshot) {
        RetrievalCandidateVO vo = new RetrievalCandidateVO();
        if (snapshot != null) {
            org.springframework.beans.BeanUtils.copyProperties(snapshot, vo);
            if (snapshot.getResult() != null) {
                org.springframework.beans.BeanUtils.copyProperties(snapshot.getResult(), vo);
                vo.setCandidateType(value(snapshot.getResult().getCandidateType()));
                vo.setScoreType(value(snapshot.getResult().getScoreType()));
            }
        }
        vo.setSnapshotAvailable(snapshot != null && snapshot.getResult() != null
                && snapshot.getResult().getCandidateType() != null
                && hasText(snapshot.getResult().getCandidateId()) && hasText(snapshot.getName()));
        vo.setContentAvailable(snapshot != null && hasText(snapshot.getContent()));
        return vo;
    }

    private static String value(Enum<?> value) { return value == null ? null : value.name(); }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
