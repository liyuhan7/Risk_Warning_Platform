package com.riskwarning.org.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 上传确认任务。确认时冻结文件快照，执行阶段不再依赖 Redis 元数据存活。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadConfirmDto implements Serializable {

    private static final long serialVersionUID = 1123121L;

    /** 稳定任务标识：projectId + sorted(uploadIds) 确定性派生，同一批上传重复确认只产生一个任务。 */
    private String taskId;

    private Long projectId;

    private Long userId;

    /** 确认时刻的 UploadFileDto 快照。 */
    private List<UploadFileDto> files;

    /** 旧 Redis 消息兼容字段；重试次数由 Durable Work 管理。 */
    @Deprecated
    private Integer retryCount;

}
