package com.riskwarning.common.constants;

import java.io.File;
import java.time.LocalDate;

public class Constants {

    public static final String FILE_TEMP_DIR =  "storage" + File.separator + "temp";

    public static final String FILE_PERSIST_DIR = "storage" + File.separator + "persist";

    public static final String FILE_INTERNAL_DIR =  "storage" + File.separator + "internal";

    public static final String FILE_PROCESSING_DIR =  "storage" + File.separator + "processing";

    public static final Long FILE_SIZE_LIMIT = 10 * 1024 * 1024L; // 10 MB

    public static final Integer FILE_CHUNK_SIZE = 1024 * 1024; // 1 MB

    public static final Integer FILE_TOTAL_CHUNKS = 10;


    public static final Integer FILE_AMOUNT_LIMIT = 5;


    public static String getTempFileDirPath(Long projectId, String uploadId) {
        return new File("").getAbsolutePath() + File.separator + Constants.FILE_TEMP_DIR + File.separator + projectId + File.separator + uploadId;
    }

    public static String getProcessingFileDirPath(Long projectId) {
        return new File("").getAbsolutePath() + File.separator +  Constants.FILE_PROCESSING_DIR + File.separator + projectId;
    }

    public static String getPersistFileDirPath(Long projectId) {
        return new File("").getAbsolutePath() + File.separator +  Constants.FILE_PERSIST_DIR + File.separator + projectId;
    }

    public static String getInternalDirPath(Long projectId) {
        return new File("").getAbsolutePath() + File.separator +  Constants.FILE_INTERNAL_DIR + File.separator + projectId;
    }
}
