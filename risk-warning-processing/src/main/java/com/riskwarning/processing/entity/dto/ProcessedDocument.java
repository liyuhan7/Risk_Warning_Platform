package com.riskwarning.processing.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 文档处理后的内部文件及其持久化源身份。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedDocument {

    private Long sourceDocumentId;
    private String internalFilePath;
}
