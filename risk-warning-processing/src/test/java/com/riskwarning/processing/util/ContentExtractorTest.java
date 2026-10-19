package com.riskwarning.processing.util;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本片段提取单元测试。
 *
 * 覆盖曾导致证据质量下降的边界条件：
 * 1. 页文本含换行时的中文字符检测；
 * 2. 段落必须各自独立成片段，保证一段落对应一条行为数据；
 * 3. PDF 抽取的版式折行（单换行）不得当作句子边界切断词语；
 * 4. 超长段落只能在句末切分，且不得丢失原文。
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

    @Test
    void shouldStripPageNumberFooterFromEvidence() {
        // PDF 页脚页码常与末句同行，不得并入证据正文
        FileScanner.PageContent page = new FileScanner.PageContent();
        page.setPageNumber(1);
        page.setText("各记录的单位、人员和期间独立；同名行业不代表同一用工事实。 1 / 3\n");
        FileScanner.ScannedDocument document = new FileScanner.ScannedDocument();
        document.setFullText(page.getText());
        document.setTotalPages(3);
        document.setPages(Collections.singletonList(page));

        List<ContentExtractor.TextSegment> segments = contentExtractor.extract(document);

        assertEquals(1, segments.size());
        assertEquals("各记录的单位、人员和期间独立；同名行业不代表同一用工事实。",
                segments.get(0).getText(), "页脚页码应被剥离");
    }

    @Test
    void shouldKeepFractionWhenItDoesNotMatchPageNumbering() {
        // 页码与总页数不吻合时视为正文，不得误删
        FileScanner.PageContent page = new FileScanner.PageContent();
        page.setPageNumber(1);
        page.setText("抽查样本合格比例为 4 / 8，其余项另行说明待补充材料。 4 / 8\n");
        FileScanner.ScannedDocument document = new FileScanner.ScannedDocument();
        document.setFullText(page.getText());
        document.setTotalPages(9);
        document.setPages(Collections.singletonList(page));

        List<ContentExtractor.TextSegment> segments = contentExtractor.extract(document);

        assertTrue(segments.get(0).getText().contains("4 / 8"), "非页脚的分数必须保留");
    }

    @Test
    void shouldNotTreatPdfLineWrapAsSentenceBoundary() {
        // PDF 抽取只输出单换行，折行落在词中间时不得切开词语
        StringBuilder pageText = new StringBuilder();
        for (int index = 0; index < 20; index++) {
            pageText.append("第").append(index).append("项事实说明内容足以通过最小长度过滤。\n");
        }
        pageText.append("岗位说明要求识图、设备设定和独立加工能力，但人事\n")
                .append("部书面确认此次既未核验技能证明，也未组织操作考核或形成能力与岗位匹配结论。\n");

        List<ContentExtractor.TextSegment> segments =
                contentExtractor.extract(buildDocument(pageText.toString()));

        String joined = segments.stream()
                .map(ContentExtractor.TextSegment::getText)
                .collect(Collectors.joining());
        assertTrue(joined.contains("但人事部书面确认"), "版式折行不得切断词语");
        for (ContentExtractor.TextSegment segment : segments) {
            assertFalse(segment.getText().endsWith("但人事"), "片段不得终止在半句上");
        }
    }

    @Test
    void shouldPackWholeSentencesWhenParagraphExceedsMaxLength() {
        // 超长段落按整句装箱：只能在句末切分，且不得丢失或改写原文
        StringBuilder pageText = new StringBuilder();
        for (int index = 0; index < 40; index++) {
            pageText.append("第").append(index).append("项说明企业已完成相关审查并要求留存记录。");
        }

        List<ContentExtractor.TextSegment> segments =
                contentExtractor.extract(buildDocument(pageText.toString()));

        String joined = segments.stream()
                .map(ContentExtractor.TextSegment::getText)
                .collect(Collectors.joining());
        assertEquals(pageText.toString(), joined, "切分不得丢失或改写原文");
        for (ContentExtractor.TextSegment segment : segments) {
            assertTrue(segment.getText().endsWith("。"), "超长段落只能在句末切分");
            assertTrue(segment.getText().length() <= 500, "片段应在长度上限内尽量装箱");
        }
    }
}
