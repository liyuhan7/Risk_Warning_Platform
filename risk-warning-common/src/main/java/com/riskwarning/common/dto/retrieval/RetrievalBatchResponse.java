package com.riskwarning.common.dto.retrieval;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalBatchResponse {
    @Builder.Default
    private String schemaVersion = "1.0";
    private List<RetrievalBatchItem> items;
}
