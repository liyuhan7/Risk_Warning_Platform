package com.riskwarning.org.service;

import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上传确认成功后的恢复输入清理：按 uploadId 删除分片元数据与临时目录。
 * 只在业务事务提交后调用；清理本身幂等，失败可随任务重试再次执行。
 */
@Component
@Slf4j
public class UploadConfirmCleanup {

    private final RedisUtil redisUtil;

    public UploadConfirmCleanup(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    public void cleanup(UploadConfirmDto task) {
        if (task == null || task.getProjectId() == null) {
            return;
        }
        String uploadInfoKey = String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, task.getProjectId());
        String fileHashKey = String.format(RedisKey.REDIS_KEY_FILE, task.getProjectId());
        List<UploadFileDto> files = task.getFiles() != null && !task.getFiles().isEmpty()
                ? task.getFiles()
                : legacyUploads(uploadInfoKey);
        for (UploadFileDto file : files) {
            if (file == null || file.getUploadId() == null || file.getUploadId().trim().isEmpty()) {
                continue;
            }
            redisUtil.hdel(uploadInfoKey, file.getUploadId());
            redisUtil.del(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, file.getUploadId()));
            FileUtils.delDirectory(Constants.getTempFileDirPath(task.getProjectId(), file.getUploadId()));
            if (file.getFileHash() != null && !file.getFileHash().trim().isEmpty()) {
                redisUtil.setRemove(fileHashKey, file.getFileHash());
            }
        }
    }

    /** 旧格式消息没有快照，按 Redis 上传元数据逐条清理；元数据过期则无可清理项。 */
    private List<UploadFileDto> legacyUploads(String uploadInfoKey) {
        Map<Object, Object> uploadFileMap = redisUtil.hmget(uploadInfoKey);
        if (uploadFileMap == null || uploadFileMap.isEmpty()) {
            log.warn("上传元数据已过期，跳过遗留消息清理: key={}", uploadInfoKey);
            return new ArrayList<>();
        }
        List<UploadFileDto> files = new ArrayList<>();
        for (Object value : uploadFileMap.values()) {
            if (value instanceof UploadFileDto) {
                files.add((UploadFileDto) value);
            }
        }
        return files;
    }
}
