package com.riskwarning.common.po.behavior;

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

    private Long projectId;

    private String description;

    private String type;

    private String dimension;

    private List<String> tags;

    private String status;

    private Double quantitativeData;

    private LocalDateTime behaviorDate;

    private List<Float> descriptionVector;

    private LocalDateTime createdAt;


}
