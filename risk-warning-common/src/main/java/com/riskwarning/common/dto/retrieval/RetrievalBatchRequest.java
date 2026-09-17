package com.riskwarning.common.dto.retrieval;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalBatchRequest {
    @Builder.Default
    private String schemaVersion = "1.0";
    private AnalysisScope scope;
    private List<RetrievalRequest> requests;
}
