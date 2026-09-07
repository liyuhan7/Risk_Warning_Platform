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

    /** StructuredBehavior 契约版本；旧 ES 文档缺失时保持 null。 */
    private String schemaVersion;

    private String id;

    private Long projectId;

    /** 本次分析所属评估；存量 ES 文档缺失时保持 null。 */
    private Long assessmentId;

    /** 本次分析运行身份；存量 ES 文档缺失时保持 null。 */
    private String analysisRunId;

    /** 产生该事实的上传文件身份；存量 ES 文档缺失时保持 null。 */
    private Long sourceDocumentId;

    private String description;

    /** 企业事实三元组，旧 Batch 链路暂不填充。 */
    private String subject;

    private String action;

    private String object;

    private String type;

    private String dimension;

    private List<String> tags;

    private String status;

    private Double quantitativeData;

    /** 数值事实的单位；仅在 quantitativeData 有值时由新抽取链填充。 */
    private String quantitativeUnit;

    private LocalDateTime behaviorDate;

    /** 抽取置信度；旧链产物暂不填充。 */
    private Double confidence;

    /** 对应 EvidenceChunk 的稳定 ID；引用完整性由新抽取链校验。 */
    private List<String> evidenceIds;

    /** 事实抽取使用的模型和 Prompt 版本；旧链产物暂不填充。 */
    private String extractionModel;

    private String extractionPromptVersion;

    private List<Float> descriptionVector;

    private LocalDateTime createdAt;


}
