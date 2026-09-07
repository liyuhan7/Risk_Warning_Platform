package com.riskwarning.common.dto.fact;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Fact Extraction Provider 返回正文解析后的根对象。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FactExtractionResponse {

    private List<ExtractedFact> facts;
}
