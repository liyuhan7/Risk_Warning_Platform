package com.riskwarning.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class StringUtils {

    /** 同一上传任务始终映射到同一持久文件。 */
    public static String generateFileName(Long projectId, String uploadId) {
        if (projectId == null || uploadId == null || uploadId.trim().isEmpty()) {
            throw new IllegalArgumentException("projectId 和 uploadId 不能为空");
        }
        return projectId + "_" + uploadId;
    }

    public static String generateMessageId() {
        return "msg_" + UUID.randomUUID();
    }

    public static String generateTraceId() {
        return "trace_" + UUID.randomUUID();
    }

    /** 固定上游消息只能派生出一个同类型下游消息。 */
    public static String deriveMessageId(String sourceId, String stage) {
        if (sourceId == null || sourceId.trim().isEmpty()
                || stage == null || stage.trim().isEmpty()) {
            throw new IllegalArgumentException("sourceId 和 stage 不能为空");
        }
        return sourceId + ":" + stage;
    }

    /**
     * 上传确认稳定任务 ID：同一项目同一批 uploadId 重复确认只得到同一个任务身份，
     * 排序保证与入队顺序无关，哈希保证长度不超过 t_durable_work.task_key 上限。
     */
    public static String deriveUploadTaskId(Long projectId, List<String> uploadIds) {
        if (projectId == null || uploadIds == null || uploadIds.isEmpty()) {
            throw new IllegalArgumentException("projectId 和 uploadIds 不能为空");
        }
        List<String> sorted = new ArrayList<>(uploadIds);
        Collections.sort(sorted);
        for (String uploadId : sorted) {
            if (uploadId == null || uploadId.trim().isEmpty()) {
                throw new IllegalArgumentException("uploadId 不能为空");
            }
        }
        return "upload:" + projectId + ":" + sha256Hex(String.join(",", sorted));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                output.append(String.format("%02x", item & 0xff));
            }
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("计算上传任务哈希失败", exception);
        }
    }
}
