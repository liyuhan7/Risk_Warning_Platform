package com.riskwarning.report.entity.vo.risk;


import com.riskwarning.common.po.risk.RelatedIndicator;
import com.riskwarning.common.po.risk.RelatedRegulation;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RiskVO {
    private String id;

    private Long projectId;

    private Long assessmentId;

    private String name;

    private String dimension;

    private String description;

    private String riskLevel;

    private double probability;

    private double impact;

    private double detectability;

    private String status;

    private String processingStatus;

    private String responsibleParty;

    private String[] affectedObjects;

    private String impactScope;

    private String countermeasures;

    private List<RelatedIndicator> relatedIndicators;

    private List<RelatedRegulation> relatedRegulations;

    private LocalDateTime createAt;
}
