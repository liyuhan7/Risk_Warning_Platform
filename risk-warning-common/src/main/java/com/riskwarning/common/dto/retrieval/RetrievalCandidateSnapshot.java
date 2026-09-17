package com.riskwarning.common.dto.retrieval;

import lombok.*;

/** 跨服务传递的候选快照；评分契约仍由 result 承担。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalCandidateSnapshot {
    private RetrievalResult result;
    private String name;
    private String content;
    private String retrievalTextHash;
    /** 法规原文 SHA-256；指标候选为空。 */
    private String contentHash;
    private Integer indicatorLevel;
    private String dimension;
    private String type;
    private Double maxScore;
}
