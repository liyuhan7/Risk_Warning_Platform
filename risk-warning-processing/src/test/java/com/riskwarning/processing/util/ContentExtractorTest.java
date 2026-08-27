package com.riskwarning.processing.util;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 文本片段提取单元测试。
 *
 * 覆盖两个曾导致短文档静默产出 0 个片段的边界条件：
 * 1. 页文本含换行时的中文字符检测；
 * 2. 段落必须各自独立成片段，保证一段落对应一条行为数据。
 */
class ContentExtractorTest {

    private final ContentExtractor contentExtractor = new ContentExtractor();

    private FileScanner.ScannedDocument buildDocument(String pageText) {
        FileScanner.PageContent page = new FileScanner.PageContent();
        page.setPageNumber(1);
        page.setText(pageText);

        FileScanner.ScannedDocument document = new FileScanner.ScannedDocument();
        document.setFullText(pageText);
        document.setPages(Collections.singletonList(page));
        return document;
    }

    @Test
    void shouldKeepChineseTextContainingLineBreaks() {
        // 全文不足 500 字符时整页会作为单个含换行片段，此时不得被判定为无中文内容
        String pageText = "第一段：公司生产基地尚未取得排污许可证。\n第二段：出口批次铅含量超出限值要求。\n";

        List<ContentExtractor.TextSegment> segments = contentExtractor.extract(buildDocument(pageText));

        assertFalse(segments.isEmpty(), "含换行的中文文本不应被整体丢弃");
    }

    @Test
    void shouldSplitParagraphsIntoIndependentSegments() {
        // 段落以双换行分隔时，每个段落应各自成为一个片段
        String pageText = "第一段内容足够长可以通过最小长度过滤。\n\n"
                + "第二段内容同样足够长可以通过过滤。\n\n"
                + "第三段内容也足够长可以通过过滤。\n\n";

        List<ContentExtractor.TextSegment> segments = contentExtractor.extract(buildDocument(pageText));

        assertEquals(3, segments.size(), "每个段落应独立成为一个片段");
    }

    @Test
    void shouldFilterMeaninglessContent() {
        // 页码、纯数字与无中文内容仍需被过滤
        String pageText = "12345\n\n第 3 页\n\nOnly English Content Here\n\n";

        List<ContentExtractor.TextSegment> segments = contentExtractor.extract(buildDocument(pageText));

        assertEquals(0, segments.size(), "无意义内容应被过滤");
    }
}
