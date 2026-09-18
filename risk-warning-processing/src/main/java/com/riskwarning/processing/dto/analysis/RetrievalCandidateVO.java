package com.riskwarning.processing.dto.analysis;

import lombok.*;
import java.util.List;

/** 分析概览只读展示契约。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalCandidateVO {
    private String candidateType;
    private String candidateId;
    private String name;
    private String content;
    private Double score;
    private String scoreType;
    private Integer rank;
    private String retrievalTextHash;
    private String contentHash;
    private Integer indicatorLevel;
    private String dimension;
    private String type;
    private Double maxScore;
    private boolean snapshotAvailable;
}
