package com.riskwarning.processing.dto;

import com.riskwarning.common.po.evidence.EvidenceChunk;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.format.DateTimeFormatter;

/**
 * 证据回溯展示契约。
 *
 * 页面据此呈现文件名、页码、段序号、证据 ID 与原文；时间统一格式化为字符串，
 * 避免前端依赖各服务的 Jackson 时间序列化差异。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceVO {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String evidenceId;

    private Long sourceDocumentId;

    private String sourceFileName;

    /** 页码未知时为 null，页面须显示“页码未知”而不是空占位。 */
    private Integer pageNumber;

    private Integer segmentIndex;

    private Integer charStart;

    private Integer charEnd;

    private String text;

    private String textHash;

    private String createdAt;

    public static EvidenceVO from(EvidenceChunk chunk) {
        return EvidenceVO.builder()
                .evidenceId(chunk.getId())
                .sourceDocumentId(chunk.getSourceDocumentId())
                .sourceFileName(chunk.getSourceFileName())
                .pageNumber(chunk.getPageNumber())
                .segmentIndex(chunk.getSegmentIndex())
                .charStart(chunk.getCharStart())
                .charEnd(chunk.getCharEnd())
                .text(chunk.getText())
                .textHash(chunk.getTextHash())
                .createdAt(chunk.getCreatedAt() == null ? null : chunk.getCreatedAt().format(TIME_FORMATTER))
                .build();
    }
}
