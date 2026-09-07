package com.riskwarning.processing.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 写入内部 JSONL 的可追踪文本片段。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSegmentRecord {

    private Long sourceDocumentId;
    private Integer pageNumber;
    private Integer segmentIndex;
    private String text;
}
