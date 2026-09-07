package com.riskwarning.org.service.impl;


import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.constants.RedisKey;
import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.enums.FileTypeEnum;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.FileRepository;
import com.riskwarning.org.service.FileService;
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

    @Override
    public String initUpload(Long projectId, String fileHash, Long fileSize, Integer totalChunks,
                             String fileType, String originalFileName) {
        validateOriginalFileName(originalFileName);
        String redisFileKey = String.format(RedisKey.REDIS_KEY_FILE, projectId);
        // 检查该项目是否已经上传过文件进行解析
        if(redisUtil.sHasKey(redisFileKey, fileHash)){
            throw new BusinessException("文件已存在，无法重复上传");
        }
        // 短时间快速触发会导致读失效
        if(redisUtil.sGetSetSize(redisFileKey) >= Constants.FILE_AMOUNT_LIMIT){
            throw new BusinessException("项目上传文件数量已达上限，无法上传更多文件");
        }
        //检查文件类型是否支持
        if(!FileTypeEnum.contains(fileType)){
            throw new BusinessException("不支持的文件类型，无法上传");
        }
        // 检查文件大小是否超限
        if(fileSize > Constants.FILE_SIZE_LIMIT){
            throw new BusinessException("文件大小超出限制，无法上传");
        }
        //检查分片数目是否超限
        if(totalChunks > Constants.FILE_TOTAL_CHUNKS){
            throw new BusinessException("文件分片数目超出限制，无法上传");
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
        redisUtil.hset(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId), uploadId, uploadFileDto, RedisKey.REDIS_KEY_UPLOAD_INFO_EXPIRE_TIME_SECONDS);
        return uploadId;
    }

    /** 原始名只能作为展示字段，拒绝路径和控制字符，避免其被误作服务器文件路径。 */
    private void validateOriginalFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.trim().isEmpty()
                || originalFileName.length() > 512
                || originalFileName.indexOf('/') >= 0 || originalFileName.indexOf('\\') >= 0
                || originalFileName.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException("原始文件名不合法");
        }
    }

    @Override
    public void uploadChunk(Long projectId, String uploadId, Integer chunkIndex, MultipartFile file) {
        UploadFileDto uploadFileDto = (UploadFileDto) redisUtil.hget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId), uploadId);
        if(uploadFileDto == null){
            throw new BusinessException("上传任务不存在或已过期，请重新初始化上传");
        }
        if(chunkIndex >= uploadFileDto.getTotalChunks()){
            throw new BusinessException("分片索引超过了预先设定分片数目, 请检查后重新上传");
        }
        long addChunkRes = redisUtil.sSetAndTime(
                String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId),
                RedisKey.REDIS_KEY_UPLOAD_CHUNKS_EXPIRE_TIME_SECONDS,
                chunkIndex
        );
        if(addChunkRes == 0){
            throw new BusinessException("该分片已上传，无需重复上传");
        }
        String targetFilePath = uploadFileDto.getFilePath() + File.separator + chunkIndex;
        try {
            FileUtils.transferFile(file, targetFilePath);
        } catch (Exception e) {
            redisUtil.setRemove(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadId), chunkIndex);
        }
    }

    @Override
    public void deleteTempFile(Long projectId, String uploadId) {
        try {
            // 删除Redis缓存
            String redisFileKey = String.format(RedisKey.REDIS_KEY_FILE, projectId);
            UploadFileDto uploadFileDto = (UploadFileDto) redisUtil.hget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId), uploadId);
            if(!redisUtil.sHasKey(redisFileKey, uploadId) || uploadFileDto == null){
                throw new BusinessException("上传任务不存在或已过期");
            }
            redisUtil.setRemove(redisFileKey, uploadFileDto.getFileHash());
            redisUtil.hdel(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId), uploadId);
        } finally {
            FileUtils.delDirectory(Constants.getTempFileDirPath(projectId, uploadId));
        }
    }

    @Override
    public void confirmUpload(Long projectId) {
        // 检查上传任务是否存在
        Map<Object, Object> uploadFileMap = redisUtil.hmget(String.format(RedisKey.REDIS_KEY_FILE_UPLOAD_INFO, projectId));
        if(uploadFileMap == null || uploadFileMap.isEmpty()){
            throw new BusinessException("上传任务不存在或已过期");
        }
        for(Object value : uploadFileMap.values()) {
            UploadFileDto uploadFileDto = (UploadFileDto) value;
            Set<Object> chunksSet = redisUtil.sGet(String.format(RedisKey.REDIS_KEY_UPLOAD_CHUNKS, uploadFileDto.getUploadId()));
            if(chunksSet.size() != uploadFileDto.getTotalChunks()){
                throw new BusinessException("文件分片上传未完成，无法确认上传");
            }
        }
        // 将确认上传任务放入队列，异步处理文件合并和入
        redisUtil.lSet(
                RedisKey.REDIS_KEY_CONFIRMED_FILE_QUEUE,
                UploadConfirmDto.builder()
                        .projectId(projectId)
                        .userId(UserContext.getUser().getId())
                        .retryCount(0)
                        .build()
        );
    }
}
