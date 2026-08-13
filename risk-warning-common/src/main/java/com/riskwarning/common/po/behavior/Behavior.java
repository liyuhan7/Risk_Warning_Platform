package com.riskwarning.common.po.behavior;

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
public class Behavior {

    private String id;

    @JsonProperty("project_id")
    private Long projectId;

    private String description;

    private String type;

    private String dimension;

    private List<String> tags;

    private String status;

    private Double quantitativeData;

    private LocalDateTime behaviorDate;

    @JsonProperty("description_vector")
    private List<Float> descriptionVector;

    private LocalDateTime createdAt;


}
