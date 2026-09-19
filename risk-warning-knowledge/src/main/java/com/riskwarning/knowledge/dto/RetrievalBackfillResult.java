package com.riskwarning.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 检索字段回填统计及逐项失败明细。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalBackfillResult {
    private long total;
    private long updated;
    private long skipped;
    private long failed;
    private long elapsedMillis;
    @Builder.Default
    private List<Failure> failures = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failure {
        private String index;
        private String id;
        private String reason;
    }
}
