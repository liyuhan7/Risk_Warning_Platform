package com.riskwarning.common.dto.retrieval;

import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单条检索候选，rank 在类型内从 1 连续编号。
 * HYBRID 使用 dense top-N ∪ BM25 top-N 按 ES ID 合并后的窗口 max 归一化 BM25；
 * dense 独有候选的 BM25 为 0，最大值不存在或不大于 0 时整路为 0。
 * matchedFilters 的表达式与 enabled/applied 状态一起解释，禁用不表示命中过滤。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalResult {
    @Builder.Default
    private String schemaVersion = "1.0";
    private RetrievalCandidateType candidateType;
    private String candidateId;
    private Double score;
    private ScoreType scoreType;
    private Integer rank;
    private Map<String, String> matchedFilters;
    private String embeddingModel;
    private String embeddingVersion;
    private String behaviorId;
    private String analysisRunId;
    private Long assessmentId;
    /** 东八区时间。 */
    private LocalDateTime retrievedAt;
}
