package com.riskwarning.common.po.regulation;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Regulation {

    private String id;

    private String name;

    private String type;

    private String dimension;

    private List<String> industry;

    private List<String> tags;

    private String region;

    private String applicableSubject;

    private String fullText;

    private String direction;

    private Double quantitativeIndicator;

    @JsonProperty("full_text_vector")
    private List<Float> fullTextVector;

    private LocalDateTime createdAt;
}
