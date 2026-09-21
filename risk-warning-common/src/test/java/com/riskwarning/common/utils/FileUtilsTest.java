package com.riskwarning.common.utils;

import com.riskwarning.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void unionUsesNumericOrderAndOverwritesRetryTarget() throws Exception {
        Path chunks = tempDir.resolve("chunks");
        Files.createDirectories(chunks);
        Files.write(chunks.resolve("10"), "C".getBytes(StandardCharsets.UTF_8));
        Files.write(chunks.resolve("2"), "B".getBytes(StandardCharsets.UTF_8));
        Files.write(chunks.resolve("0"), "A".getBytes(StandardCharsets.UTF_8));
        Path target = tempDir.resolve("merged.bin");
        Files.write(target, "OLD".getBytes(StandardCharsets.UTF_8));

        FileUtils.union(chunks.toString(), target.toString(), false);
        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));

        FileUtils.union(chunks.toString(), target.toString(), false);
        assertArrayEquals("ABC".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
        assertTrue(Files.exists(chunks.resolve("0")));
    }

    @Test
    void rejectsEmptyChunkDirectory() throws Exception {
        Path chunks = tempDir.resolve("empty");
        Files.createDirectories(chunks);

        assertThrows(BusinessException.class,
                () -> FileUtils.union(chunks.toString(), tempDir.resolve("out").toString(), false));
    }
}
