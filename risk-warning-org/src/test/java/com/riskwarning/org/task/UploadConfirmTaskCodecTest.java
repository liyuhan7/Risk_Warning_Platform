package com.riskwarning.org.task;

import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadConfirmTaskCodecTest {

    private final UploadConfirmTaskCodec codec = new UploadConfirmTaskCodec();

    @Test
    void roundTripsSnapshotPayload() {
        UploadFileDto file = UploadFileDto.builder()
                .projectId(7L).uploadId("upload-1").userId(8L)
                .filePath("/tmp/upload-1").fileHash("hash-1")
                .totalChunks(3).fileSuffix("pdf").originalFileName("制度.pdf")
                .build();
        UploadConfirmDto task = UploadConfirmDto.builder()
                .taskId("upload:7:abc").projectId(7L).userId(8L)
                .files(Collections.singletonList(file)).build();

        UploadConfirmDto decoded = codec.decode(codec.encode(task));

        assertEquals("upload:7:abc", decoded.getTaskId());
        assertEquals(7L, decoded.getProjectId());
        assertEquals(1, decoded.getFiles().size());
        assertEquals("upload-1", decoded.getFiles().get(0).getUploadId());
        assertEquals("制度.pdf", decoded.getFiles().get(0).getOriginalFileName());
        assertNotNull(decoded.getFiles().get(0).getTotalChunks());
    }

    @Test
    void rejectsIncompleteTaskForEncodeAndDecode() {
        UploadConfirmDto missingFiles = UploadConfirmDto.builder()
                .taskId("upload:7:abc").projectId(7L).build();

        assertThrows(IllegalStateException.class, () -> codec.encode(missingFiles));
        assertThrows(IllegalStateException.class,
                () -> codec.encode(UploadConfirmDto.builder().projectId(7L)
                        .files(Collections.singletonList(new UploadFileDto())).build()));
    }

    @Test
    void rejectsBlankOrCorruptPayload() {
        assertThrows(IllegalStateException.class, () -> codec.decode(" "));
        assertThrows(IllegalStateException.class, () -> codec.decode("not-json"));
        String emptyFiles = "{\"taskId\":\"upload:7:abc\",\"projectId\":7,\"files\":[]}";
        assertThrows(IllegalStateException.class, () -> codec.decode(emptyFiles));
    }

    @Test
    void decodesLegacyListFieldShape() {
        // files 以数组形态序列化，字段顺序无关
        String payload = "{\"projectId\":7,\"userId\":8,\"taskId\":\"upload:7:abc\","
                + "\"files\":[{\"uploadId\":\"u-1\",\"projectId\":7,\"totalChunks\":2}]}";
        UploadConfirmDto decoded = codec.decode(payload);

        List<UploadFileDto> files = decoded.getFiles();
        assertEquals(1, files.size());
        assertEquals("u-1", files.get(0).getUploadId());
    }
}
