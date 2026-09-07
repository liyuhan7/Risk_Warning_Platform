package com.riskwarning.org.service;

import com.riskwarning.common.po.file.ProjectFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface FileService {

    public String initUpload(Long projectId, String fileHash, Long fileSize, Integer totalChunks,
                             String fileType, String originalFileName);

    public void uploadChunk(Long projectId, String uploadId, Integer chunkIndex, MultipartFile file);

    public void deleteTempFile(Long projectId, String uploadId);

    public void confirmUpload(Long projectId);
}
