package com.riskwarning.org.entity.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;

@Data
public class FileUploadChunkRequest {

    @NotNull
    @Positive
    private Long projectId;

    @NotEmpty
    private String uploadId;

    @NotNull
    @PositiveOrZero
    private Integer chunkIndex;

    @NotNull
    private MultipartFile chunkData;
}
