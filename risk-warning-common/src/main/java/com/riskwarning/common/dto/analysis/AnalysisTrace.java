package com.riskwarning.common.dto.analysis;

import com.riskwarning.common.dto.retrieval.RetrievalCandidateSnapshot;
import com.riskwarning.common.po.analysis.AnalysisResult;
import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisTrace {
    private String behaviorId;
    private List<String> evidenceIds;
    private String queryText;
    private String queryTemplateVersion;
    private String filterVersion;
    private Boolean filterEnabled;
    private List<RetrievalCandidateSnapshot> candidates;
    private AnalysisResult analysisResult;
    private RuleEvaluation ruleEvaluation;
}
