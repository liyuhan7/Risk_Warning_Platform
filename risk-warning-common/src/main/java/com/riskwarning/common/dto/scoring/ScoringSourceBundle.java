package com.riskwarning.common.dto.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;

/** 指标评分的完整依据，包含实际文本；不能只保存无法回读的摘要。 */
@Data
public class ScoringSourceBundle {
    private String datasetVersion;
    private JsonNode indicator;
    private List<Source> relations;

    public String bundleHash() { return ScoringJson.hash(this); }

    @Data
    public static class Source {
        private String regulationId;
        private JsonNode regulation;
        private JsonNode relation;
        private JsonNode reference;
        private String fullTextHash;
        private String relationHash;
        private String referenceSourcesHash;
    }
}
