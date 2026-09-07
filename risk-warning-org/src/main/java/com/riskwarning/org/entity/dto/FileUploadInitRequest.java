package com.riskwarning.org.entity.dto;

import lombok.Data;
import lombok.NonNull;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.Max;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class FileUploadInitRequest {

    @NotNull
    private Long projectId;

    @NotBlank
    private String fileHash;

    @NotNull
    @Max(1024 * 1024 * 10) // 最大10MB
    private Long fileSize;

    @NotNull
    private Integer totalChunks;

    @NotBlank
    private String fileType;

    /** 原始文件名仅用于展示和证据回溯，不参与服务器存储路径生成。 */
    @NotBlank
    @Size(max = 512)
    private String fileName;

}
