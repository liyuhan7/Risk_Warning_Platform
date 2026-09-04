package com.riskwarning.common.po.regulation;

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

    /** P0-11 决议 3.2：语义为合规领域，ES 字段已改名 complianceDomain */
    private List<String> complianceDomain;

    private List<String> tags;

    private String region;

    private String applicableSubject;

    private String fullText;

    private String direction;

    private Double quantitativeIndicator;

    private List<Float> fullTextVector;

    private LocalDateTime createdAt;
}
