package com.riskwarning.common.dto.retrieval;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalBatchItem {
    private String behaviorId;
    private RetrievalBatchStatus status;
    private List<RetrievalCandidateSnapshot> candidates;
}
