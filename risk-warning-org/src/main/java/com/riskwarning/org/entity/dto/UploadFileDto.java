package com.riskwarning.org.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UploadFileDto implements Serializable {

    private static final long serialVersionUID = 112312L;

    private String uploadId;

    private Long projectId;

    private Long userId;

    private String fileHash;

    // 总分片数
    private Integer totalChunks;

    private String filePath;

    private String fileSuffix;

    /** 浏览器上报的原始文件名；旧 Redis 上传任务反序列化时允许为空。 */
    private String originalFileName;
}
