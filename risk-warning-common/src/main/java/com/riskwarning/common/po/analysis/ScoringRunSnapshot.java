package com.riskwarning.common.po.analysis;

import com.riskwarning.common.dto.scoring.ScoringRuleSet.IndicatorRule;
import com.riskwarning.common.dto.scoring.ScoringSourceBundle;
import com.riskwarning.common.dto.scoring.FactExtractionPlan;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Data;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.TypeDef;
import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.*;

/** 本次评分使用的规则、依据和行为清单，历史查询不依赖实时知识库。 */
@Data
@Entity
@Table(name = "t_scoring_run_snapshot")
@TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
public class ScoringRunSnapshot {
    @Id @Column(name = "analysis_run_id", length = 64)
    private String analysisRunId;
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Column(name = "assessment_id", nullable = false)
    private Long assessmentId;
    @Column(name = "registry_version", nullable = false)
    private String registryVersion;
    @Column(name = "dataset_version", nullable = false, length = 64)
    private String datasetVersion;
    @Column(name = "snapshot_hash", nullable = false, length = 64)
    private String snapshotHash;
    @Type(type = "jsonb") @Column(name = "source_bundles", nullable = false, columnDefinition = "jsonb")
    private Map<String, ScoringSourceBundle> sourceBundles;
    @Type(type = "jsonb") @Column(name = "rule_definitions", nullable = false, columnDefinition = "jsonb")
    private Map<String, IndicatorRule> ruleDefinitions;
    @Type(type = "jsonb") @Column(name = "indicator_behaviors", nullable = false, columnDefinition = "jsonb")
    private Map<String, List<String>> indicatorBehaviors;
    /** 冻结模式、问题、绑定及请求边界，历史运行为空。 */
    @Type(type = "jsonb") @Column(name = "fact_extraction_plan", columnDefinition = "jsonb")
    private FactExtractionPlan factExtractionPlan;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
