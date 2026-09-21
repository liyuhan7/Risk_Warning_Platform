package com.riskwarning.org.task;

/**
 * 上传确认任务缺少可恢复输入（项目标识缺失或 Redis 上传元数据已过期）。
 * 此类任务无法通过重试恢复，应移入死信。
 */
public class UploadSnapshotMissingException extends RuntimeException {

    public UploadSnapshotMissingException(String message) {
        super(message);
    }

    public UploadSnapshotMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}
