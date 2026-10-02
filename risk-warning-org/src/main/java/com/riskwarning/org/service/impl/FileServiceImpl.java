package com.riskwarning.org.service.impl;


import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.enums.FileTypeEnum;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.FileRepository;
import com.riskwarning.org.service.FileService;
import com.riskwarning.org.task.UploadTaskQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.*;

@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private UploadTaskQueue uploadTaskQueue;

    @Autowired
    private AssessmentFlowLogger flowLogger;

    @Override
    public String initUpload(Long projectId, String fileHash, Long fileSize, Integer totalChunks,
                             String fileType, String originalFileName) {
        requireProjectId(projectId);
        if (fileHash == null || fileHash.trim().isEmpty() || fileSize == null || fileSize <= 0
                || totalChunks == null || totalChunks <= 0) {
            throw new BusinessException(400, "文件标识、大小和分片数必须有效");
        }
        validateOriginalFileName(originalFileName);
        String redisFileKey = String.format(RedisKey.REDIS_KEY_FILE, projectId);
        // 检查该项目是否已经上传过文件进行解析
        if(redisUtil.sHasKey(redisFileKey, fileHash)){
            throw new BusinessException(409, "文件已存在，无法重复上传");
        }
        // 短时间快速触发会导致读失效
        if(redisUtil.sGetSetSize(redisFileKey) >= Constants.FILE_AMOUNT_LIMIT){
            throw new BusinessException(409, "项目上传文件数量已达上限，无法上传更多文件");
        }
        //检查文件类型是否支持
        if(!FileTypeEnum.contains(fileType)){
            throw new BusinessException(400, "不支持的文件类型，无法上传");
        }
        // 检查文件大小是否超限
        if(fileSize > Constants.FILE_SIZE_LIMIT){
            throw new BusinessException(400, "文件大小超出限制，无法上传");
        }
        //检查分片数目是否超限
        if(totalChunks > Constants.FILE_TOTAL_CHUNKS){
            throw new BusinessException(400, "文件分片数目超出限制，无法上传");
        }

        String uploadId = UUID.randomUUID().toString();
        UploadFileDto uploadFileDto = UploadFileDto.builder()
                .projectId(projectId)
                .uploadId(uploadId)
                .userId(UserContext.getUser().getId())
                .filePath(Constants.getTempFileDirPath(projectId, uploadId))
                .fileHash(fileHash)
                .totalChunks(totalChunks)
                .fileSuffix(fileType)
                .originalFileName(originalFileName.trim())
                .build();

        long putHashRes = redisUtil.sSetAndTime(redisFileKey, RedisKey.REDIS_KEY_FILE_EXPIRE_TIME_SECONDS, fileHash);
        if(putHashRes == 0){
            throw new BusinessException("初始化上传失败，可能是文件已存在，请重试");
        }
        try {
            if (!redisUtil.hset(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId), uploadId,
                    uploadFileDto, RedisKey.REDIS_KEY_UPLOAD_INFO_EXPIRE_TIME_SECONDS)) {
                throw new IllegalStateException("上传任务元数据写入失败");
            }
        } catch (RuntimeException failure) {
            // 初始化元数据未保存时释放本次取得的文件标记，避免后续重试被误判为重复文件。
            try {
                redisUtil.setRemove(redisFileKey, fileHash);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        return uploadId;
    }

    /** 原始名只能作为展示字段，拒绝路径和控制字符，避免其被误作服务器文件路径。 */
    private void validateOriginalFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.trim().isEmpty()
                || originalFileName.length() > 512
                || originalFileName.indexOf('/') >= 0 || originalFileName.indexOf('\\') >= 0
                || originalFileName.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400, "原始文件名不合法");
        }
    }

    @Override
    public void uploadChunk(Long projectId, String uploadId, Integer chunkIndex, MultipartFile file) {
        requireProjectId(projectId);
        requireUploadId(uploadId);
        if (chunkIndex == null || chunkIndex < 0 || file == null || file.isEmpty()) {
            throw new BusinessException(400, "分片索引和分片内容必须有效");
        }
        UploadFileDto uploadFileDto = (UploadFileDto) redisUtil.hget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId), uploadId);
        if(uploadFileDto == null){
            throw new BusinessException(404, "上传任务不存在或已过期，请重新初始化上传");
        }
        if (!projectId.equals(uploadFileDto.getProjectId())) {
            throw new BusinessException(403, "上传文件归属项目不一致");
        }
        if(chunkIndex >= uploadFileDto.getTotalChunks()){
            throw new BusinessException(400, "分片索引超过了预先设定分片数目, 请检查后重新上传");
        }
        long addChunkRes = redisUtil.sSetAndTime(
                String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId),
                RedisKey.REDIS_KEY_UPLOAD_CHUNKS_EXPIRE_TIME_SECONDS,
                chunkIndex
        );
        if(addChunkRes == 0){
            throw new BusinessException(409, "该分片已上传，无需重复上传");
        }
        String targetFilePath = uploadFileDto.getFilePath() + File.separator + chunkIndex;
        try {
            FileUtils.transferFile(file, targetFilePath);
        } catch (Exception e) {
            // 撤销已占用的分片标记，写入失败仍需向调用方报告，允许后续重试。
            try {
                redisUtil.setRemove(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId), chunkIndex);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("文件分片写入失败", e);
        }
    }

    @Override
    public void deleteTempFile(Long projectId, String uploadId) {
        requireProjectId(projectId);
        requireUploadId(uploadId);
        String uploadInfoKey = String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId);
        String chunksKey = String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId);
        try {
            // 删除Redis缓存
            String redisFileKey = String.format(RedisKey.REDIS_KEY_FILE, projectId);
            UploadFileDto uploadFileDto = (UploadFileDto) redisUtil.hget(uploadInfoKey, uploadId);
            if (uploadFileDto == null) {
                throw new BusinessException(404, "上传任务不存在或已过期");
            }
            if (uploadFileDto.getFileHash() == null || uploadFileDto.getFileHash().trim().isEmpty()) {
                throw new IllegalStateException("上传任务缺少文件标识");
            }
            // file:hash 集合保存的是 fileHash，不是上传任务的 uploadId。
            // Set 可能先于上传元数据过期，元数据仍存在时也应完成幂等清理。
            if (redisUtil.sHasKey(redisFileKey, uploadFileDto.getFileHash())) {
                redisUtil.setRemove(redisFileKey, uploadFileDto.getFileHash());
            }
            redisUtil.hdel(uploadInfoKey, uploadId);
        } finally {
            // 分片索引与本地分片目录都属于一次上传任务，失败或重复删除也必须清理。
            redisUtil.del(chunksKey);
            FileUtils.delDirectory(Constants.getTempFileDirPath(projectId, uploadId));
        }
    }

    @Override
    public void confirmUpload(Long projectId) {
        requireProjectId(projectId);
        // 检查上传任务是否存在
        Map<Object, Object> uploadFileMap = redisUtil.hmget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId));
        if(uploadFileMap == null || uploadFileMap.isEmpty()){
            throw new BusinessException(404, "上传任务不存在或已过期");
        }
        // 冻结确认时刻的文件快照：执行阶段不依赖 Redis 元数据存活
        List<UploadFileDto> snapshot = new ArrayList<>();
        for(Object value : uploadFileMap.values()) {
            UploadFileDto uploadFileDto = (UploadFileDto) value;
            if(uploadFileDto.getUploadId() == null || !projectId.equals(uploadFileDto.getProjectId())){
                throw new BusinessException(403, "上传文件归属项目不一致");
            }
            Set<Object> chunksSet = redisUtil.sGet(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadFileDto.getUploadId()));
            if(chunksSet == null || chunksSet.size() != uploadFileDto.getTotalChunks()){
                throw new BusinessException(409, "文件分片上传未完成，无法确认上传");
            }
            snapshot.add(copyOf(uploadFileDto));
        }
        // 排序保证快照与 taskId 稳定，重试不会派生不同任务身份
        snapshot.sort(Comparator.comparing(UploadFileDto::getUploadId));
        // 将确认上传任务放入队列，异步处理文件合并和入
        String taskId = StringUtils.deriveUploadTaskId(projectId, uploadIdsOf(snapshot));
        uploadTaskQueue.enqueue(
                UploadConfirmDto.builder()
                        .taskId(taskId)
                        .projectId(projectId)
                        .userId(UserContext.getUser().getId())
                        .files(snapshot)
                        .build()
        );
        flowLogger.info(AssessmentFlowEvent.builder(
                        AssessmentFlowStage.UPLOAD_QUEUE, AssessmentFlowStatus.SUCCEEDED)
                .projectId(projectId).taskId(taskId)
                .build());
    }

    private List<String> uploadIdsOf(List<UploadFileDto> snapshot) {
        List<String> uploadIds = new ArrayList<>();
        for (UploadFileDto uploadFileDto : snapshot) {
            uploadIds.add(uploadFileDto.getUploadId());
        }
        return uploadIds;
    }

    private void requireProjectId(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new BusinessException(400, "项目 ID 必须为正数");
        }
    }

    private void requireUploadId(String uploadId) {
        if (uploadId == null || uploadId.trim().isEmpty() || uploadId.indexOf('/') >= 0
                || uploadId.indexOf('\\') >= 0 || uploadId.equals(".") || uploadId.equals("..")
                || uploadId.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400, "上传任务 ID 不合法");
        }
    }

    /** 防御性拷贝，避免快照与 Redis 反序列化对象共享可变状态。 */
    private UploadFileDto copyOf(UploadFileDto source) {
        return UploadFileDto.builder()
                .projectId(source.getProjectId())
                .uploadId(source.getUploadId())
                .userId(source.getUserId())
                .filePath(source.getFilePath())
                .fileHash(source.getFileHash())
                .totalChunks(source.getTotalChunks())
                .fileSuffix(source.getFileSuffix())
                .originalFileName(source.getOriginalFileName())
                .build();
    }
}
