package com.riskwarning.common.utils;

import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;


@Slf4j
public class FileUtils {

    public static File getFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + filePath);
        }
        return file;
    }

    public static void transferFile(MultipartFile multipartFile, String targetFilePath) {
        File targetFile = new File(targetFilePath);
        File parentDir = targetFile.getParentFile();
        if(targetFile.exists()){
            return;
        }
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        try {
            multipartFile.transferTo(targetFile);
        } catch (Exception e) {
            throw new RuntimeException("Failed to transfer file to " + targetFilePath, e);
        }
    }

    public static void combineChunks(String chunksDir, String outputFilePath, int totalChunks) {
        try {

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // remove temp chunks
            File dir = new File(chunksDir);
            if (dir.isDirectory()) {
                for (File file : dir.listFiles()) {
                    file.delete();
                }
                dir.delete();
            }
        }
    }

    public static void union(String dirPath, String toFilePath, Boolean delSource){
        File dir = new File(dirPath);
        if(!dir.exists() || !dir.isDirectory()){
            throw new BusinessException("目录不存在或不是一个目录");
        }
        File[] fileList = dir.listFiles();
        if (fileList == null || fileList.length == 0) {
            throw new BusinessException("分片目录为空");
        }

        // todo: 保存到远程存储需要改造
        File targetFile = new File(toFilePath);
        File targetParentDir = targetFile.getParentFile();
        if(!targetParentDir.exists()){
            targetParentDir.mkdirs();
        }
        try(RandomAccessFile writeFile = new RandomAccessFile(targetFile, "rw")) {
            // 重试始终覆盖同一稳定目标文件，不能向上一次的结果继续追加。
            writeFile.setLength(0);
            // 分片文件名是数字索引，字符串排序会把 10 排在 2 前面。
            Arrays.sort(fileList, new Comparator<File>() {
                public int compare(File o1, File o2) {
                    return Integer.compare(Integer.parseInt(o1.getName()),
                            Integer.parseInt(o2.getName()));
                }
            });
            for(File file : fileList){
                if(file.isFile()){
                    try(RandomAccessFile readFile = new RandomAccessFile(file, "r")) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while((len = readFile.read(buffer)) != -1){
                            writeFile.write(buffer, 0, len);
                        }
                    }
                }
//                else if(file.isDirectory()){
//                    union(file.getAbsolutePath(), toFilePath, delSource);
//                }
            }
        } catch (Exception e) {
            log.error("合并文件失败，error: {}", e.getMessage());
            throw new BusinessException("合并文件失败");
        } finally {
            if(delSource){
                for(File file : fileList){
                    if(file.isDirectory()){
                        delDirectory(file.getAbsolutePath());
                    } else {
                        file.delete();
                    }
                }
            }
        }
    }

    public static void delDirectory(String filePath) {
        File file = new File(filePath);
        if(file.exists() && file.isDirectory()){
            for(File subFile : file.listFiles()){
                if(subFile.isDirectory()){
                    delDirectory(subFile.getAbsolutePath());
                } else {
                    subFile.delete();
                }
            }
            file.delete();
        }
    }

    public static void copyDirectory(File sourceFile, File targetFile) {
        if (!sourceFile.exists()) {
            return;
        }
        if (sourceFile.isDirectory()) {
            if (!targetFile.exists()) {
                targetFile.mkdirs();
            }
            for (File subFile : sourceFile.listFiles()) {
                copyDirectory(subFile, new File(targetFile, subFile.getName()));
            }
        } else {
            try {
                Files.copy(sourceFile.toPath(), targetFile.toPath());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
