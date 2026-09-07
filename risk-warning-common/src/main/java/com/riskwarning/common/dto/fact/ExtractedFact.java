package com.riskwarning.common.dto.fact;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** LLM 按 fact-extraction-v1.0 契约输出的单条客观事实。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedFact {

    private String subject;
    private String action;
    private String object;
    private String status;
    private String behaviorDate;
    private Double quantitativeData;
    private String quantitativeUnit;
    private String description;
    private Double confidence;
    private List<String> evidenceIds;
}
