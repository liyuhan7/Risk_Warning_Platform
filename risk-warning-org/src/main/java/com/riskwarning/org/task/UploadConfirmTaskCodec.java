package com.riskwarning.org.task;

import com.alibaba.fastjson2.JSON;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import org.springframework.stereotype.Component;

/**
 * Durable Work 载荷编解码。载荷自包含 taskId 与文件快照，
 * 执行阶段不回读 Redis 上传元数据。
 */
@Component
public class UploadConfirmTaskCodec {

    public String encode(UploadConfirmDto task) {
        requireComplete(task, "写入 Durable Work");
        return JSON.toJSONString(task);
    }

    public UploadConfirmDto decode(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalStateException("上传确认任务载荷不能为空");
        }
        UploadConfirmDto task;
        try {
            task = JSON.parseObject(payload, UploadConfirmDto.class);
        } catch (Exception exception) {
            throw new IllegalStateException("上传确认任务载荷反序列化失败", exception);
        }
        requireComplete(task, "执行");
        return task;
    }

    private void requireComplete(UploadConfirmDto task, String operation) {
        if (task == null
                || task.getTaskId() == null || task.getTaskId().trim().isEmpty()
                || task.getProjectId() == null
                || task.getFiles() == null || task.getFiles().isEmpty()) {
            throw new IllegalStateException("上传确认任务缺少必要字段，无法" + operation);
        }
    }
}
