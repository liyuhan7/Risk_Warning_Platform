package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.processing.entity.dto.DocumentSegmentRecord;
import com.riskwarning.processing.util.ContentExtractor;
import com.riskwarning.processing.util.FileScanner;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentProcessingServiceTest {

    @Test
    void writesStablePageAndSegmentOrderToJsonLines() throws Exception {
        ContentExtractor extractor = mock(ContentExtractor.class);
        DocumentProcessingService service = new DocumentProcessingService();
        ObjectMapper objectMapper = new ObjectMapper();
        ReflectionTestUtils.setField(service, "contentExtractor", extractor);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        FileScanner.PageContent firstPage = page(1, "first page");
        FileScanner.PageContent secondPage = page(2, "second page");
        FileScanner.ScannedDocument firstDocument = scanned(firstPage);
        FileScanner.ScannedDocument secondDocument = scanned(secondPage);
        when(extractor.extract(firstDocument)).thenReturn(Arrays.asList(
                segment(1, "第一条材料事实"), segment(1, "第二条材料事实")));
        when(extractor.extract(secondDocument)).thenReturn(
                Collections.singletonList(segment(2, "第三条材料事实")));

        StringWriter output = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(output)) {
            service.writeSegments(writer, 101L, "source.pdf", Arrays.asList(firstPage, secondPage));
        }

        String[] lines = output.toString().split("\\R");
        assertEquals(3, lines.length);
        assertRecord(objectMapper, lines[0], 101L, 1, 0, "第一条材料事实");
        assertRecord(objectMapper, lines[1], 101L, 1, 1, "第二条材料事实");
        assertRecord(objectMapper, lines[2], 101L, 2, 2, "第三条材料事实");
    }

    private FileScanner.PageContent page(int number, String text) {
        FileScanner.PageContent page = new FileScanner.PageContent();
        page.setPageNumber(number);
        page.setText(text);
        return page;
    }

    private FileScanner.ScannedDocument scanned(FileScanner.PageContent page) {
        FileScanner.ScannedDocument document = new FileScanner.ScannedDocument();
        document.setFullText(page.getText());
        document.setPages(Collections.singletonList(page));
        document.setFileName("source.pdf");
        document.setTotalPages(2);
        return document;
    }

    private ContentExtractor.TextSegment segment(int page, String text) {
        ContentExtractor.TextSegment segment = new ContentExtractor.TextSegment();
        segment.setPageNumber(page);
        segment.setText(text);
        segment.setLength(text.length());
        return segment;
    }

    private void assertRecord(ObjectMapper mapper, String json, Long sourceDocumentId,
                              int page, int index, String text) throws Exception {
        DocumentSegmentRecord record = mapper.readValue(json, DocumentSegmentRecord.class);
        assertEquals(sourceDocumentId, record.getSourceDocumentId());
        assertEquals(Integer.valueOf(page), record.getPageNumber());
        assertEquals(Integer.valueOf(index), record.getSegmentIndex());
        assertEquals(text, record.getText());
    }
}
