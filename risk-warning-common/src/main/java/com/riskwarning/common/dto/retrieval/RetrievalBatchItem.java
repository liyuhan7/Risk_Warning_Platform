package com.riskwarning.common.dto.retrieval;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalBatchItem {
    private String behaviorId;
    private RetrievalBatchStatus status;
    private List<RetrievalCandidateSnapshot> candidates;
    /** 即使成功检索为空，也保留实际使用的模型、版本和过滤审计。 */
    private String embeddingModel;
    private String embeddingVersion;
    private Map<String, String> matchedFilters;
}
