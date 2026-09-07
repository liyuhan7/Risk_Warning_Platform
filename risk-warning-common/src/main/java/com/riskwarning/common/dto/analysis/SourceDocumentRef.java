package com.riskwarning.common.dto.analysis;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一次分析引用的单个持久化源文件。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceDocumentRef {

    private Long sourceDocumentId;
    private String filePath;
}

