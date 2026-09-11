package com.riskwarning.processing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 结构化事实查询结果。
 *
 * hasSuccessfulRun 区分两种空态：该评估尚无成功运行（评估中或已失败），
 * 与"运行成功但未抽取到事实"；二者在前端呈现的用户下一步完全不同。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BehaviorListVO {

    /** 实际查询使用的运行 ID；无成功运行时为 null。 */
    private String analysisRunId;

    /** 是否存在可读取的成功运行；false 表示评估尚无成功结果可用。 */
    private boolean hasSuccessfulRun;

    private List<StructuredBehaviorVO> behaviors;
}