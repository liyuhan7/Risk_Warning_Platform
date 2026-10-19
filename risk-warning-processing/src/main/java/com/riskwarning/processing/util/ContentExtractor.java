package com.riskwarning.processing.util;

import com.riskwarning.processing.util.FileScanner.ScannedDocument;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ContentExtractor {

    // 最小片段长度（字符数）
    private static final int MIN_SEGMENT_LENGTH = 10;

    // 最大片段长度（字符数）。超过后仍按整句装箱，不得截断句中文字
    private static final int MAX_SEGMENT_LENGTH = 500;

    /**
     * 空行才是语义段落边界。
     * PDF/Word 抽取出的版式换行是单换行，属于段落内折行，不能当作段落或句子边界，
     * 否则会在词中间切开（如“但人事”+“部书面确认”）。
     */
    private static final Pattern PARAGRAPH_SEPARATOR =
            Pattern.compile("(?:\\r?\\n[ \\t\\u3000]*){2,}");

    /** 段落内的折行，连同两侧空白一并归一化。 */
    private static final Pattern SOFT_LINE_BREAK =
            Pattern.compile("[ \\t\\u3000]*\\r?\\n[ \\t\\u3000]*");

    /** 句末标点保留在句子内，切分后不丢句读。 */
    private static final Pattern SENTENCE =
            Pattern.compile("[^。！？；!?;]+[。！？；!?;]*");

    /** PDF 页码页脚，形如“1 / 8”；仅当页码与总页数吻合时剥离，避免误删正文分数。 */
    private static final Pattern PAGE_MARKER_TAIL =
            Pattern.compile("\\s*(\\d+)\\s*/\\s*(\\d+)\\s*$");

    /**
     * 中文字符检测。
     * 必须使用 find 而非 matches：matches 要求整串匹配且点号默认不匹配换行，
     * 会导致含换行的多行片段被误判为不含中文而整体丢弃。
     */
    private static final Pattern CHINESE_CHAR = Pattern.compile("[\\u4e00-\\u9fa5]");

    /**
     * 从扫描文档中提取文本片段
     */
    public List<TextSegment> extract(ScannedDocument document) {
        if (document == null || document.getFullText() == null) {
            return new ArrayList<>();
        }
        
        List<TextSegment> allSegments = new ArrayList<>();
        
        // 按页处理
        for (FileScanner.PageContent page : document.getPages()) {
            List<TextSegment> pageSegments = extractFromPage(page, document.getTotalPages());
            allSegments.addAll(pageSegments);
        }
        
        return allSegments;
    }


    private List<TextSegment> extractFromPage(FileScanner.PageContent page, int totalPages) {
        String text = page.getText();
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // 分段处理；页脚页码先剥离，避免被并进最后一条证据
        String content = stripPageMarker(text.trim(), page.getPageNumber(), totalPages);
        List<TextSegment> segments = splitIntoSegments(content, page.getPageNumber());
        
        // 过滤无意义内容
        return segments.stream()
                .filter(this::isMeaningful)
                .collect(Collectors.toList());
    }

    /**
     * 剥离页尾页码。只在页码与当前页一致、且与文档总页数吻合时移除，
     * 防止把正文中形如“1 / 8”的分数误当页脚。
     */
    private String stripPageMarker(String text, int pageNumber, int totalPages) {
        Matcher matcher = PAGE_MARKER_TAIL.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        int current = Integer.parseInt(matcher.group(1));
        int total = Integer.parseInt(matcher.group(2));
        if (current != pageNumber || totalPages > 0 && total != totalPages) {
            return text;
        }
        return text.substring(0, matcher.start()).trim();
    }
    

    /**
     * 按“段落 → 句子”两级切分：先以空行确定段落，段落内合并版式折行；
     * 段落不超过上限时整体成为一个片段，超过上限时才按整句装箱，
     * 保证片段边界始终落在句末标点上，不会出现半句或半个词。
     */
    private List<TextSegment> splitIntoSegments(String text, int pageNumber) {
        List<TextSegment> segments = new ArrayList<>();
        for (String paragraph : PARAGRAPH_SEPARATOR.split(text)) {
            String normalized = joinSoftLineBreaks(paragraph.trim());
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.length() <= MAX_SEGMENT_LENGTH) {
                segments.add(createSegment(normalized, pageNumber));
            } else {
                segments.addAll(packSentences(normalized, pageNumber));
            }
        }
        return segments;
    }

    /**
     * 合并段落内的折行。中文与中文直接相连，避免出现“人事 部”这类被空格污染的词；
     * 只有两侧都是西文字母数字时才补一个空格，防止西文单词粘连。
     */
    private String joinSoftLineBreaks(String text) {
        Matcher matcher = SOFT_LINE_BREAK.matcher(text);
        StringBuffer joined = new StringBuffer();
        int lastEnd = 0;
        while (matcher.find()) {
            joined.append(text, lastEnd, matcher.start());
            joined.append(needsSpace(joined, text, matcher.end()) ? " " : "");
            lastEnd = matcher.end();
        }
        joined.append(text.substring(lastEnd));
        return joined.toString().trim();
    }

    private boolean needsSpace(StringBuffer joined, String text, int nextIndex) {
        if (joined.length() == 0 || nextIndex >= text.length()) {
            return false;
        }
        return isAsciiWord(joined.charAt(joined.length() - 1)) && isAsciiWord(text.charAt(nextIndex));
    }

    private boolean isAsciiWord(char value) {
        return value < 128 && Character.isLetterOrDigit(value);
    }

    /**
     * 超长段落按整句装箱：句子是原子单位，不得从中截断；
     * 单句超过上限时独占一个片段，由下游按长度校验，禁止静默截断。
     */
    private List<TextSegment> packSentences(String text, int pageNumber) {
        List<TextSegment> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Matcher matcher = SENTENCE.matcher(text);
        while (matcher.find()) {
            String sentence = matcher.group();
            if (sentence.trim().isEmpty()) {
                continue;
            }
            if (current.length() > 0
                    && current.length() + sentence.length() > MAX_SEGMENT_LENGTH) {
                segments.add(createSegment(current.toString().trim(), pageNumber));
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) {
            segments.add(createSegment(current.toString().trim(), pageNumber));
        }
        return segments;
    }


    private TextSegment createSegment(String text, int pageNumber) {
        TextSegment segment = new TextSegment();
        segment.setText(text);
        segment.setPageNumber(pageNumber);
        segment.setLength(text.length());
        return segment;
    }
    
    private boolean isMeaningful(TextSegment segment) {
        String text = segment.getText();
        
        // 过滤空文本
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        // 过滤过短的文本
        if (text.trim().length() < MIN_SEGMENT_LENGTH) {
            return false;
        }
        
        // 过滤纯数字或纯符号
        if (text.trim().matches("^[\\d\\s\\p{Punct}]+$")) {
            return false;
        }
        
        // 过滤只有标点符号的文本
        if (text.trim().matches("^[\\p{Punct}\\s]+$")) {
            return false;
        }
        
        // 过滤页眉页脚常见内容（页码、日期等）
        if (isHeaderOrFooter(text)) {
            return false;
        }
        
        // 必须包含至少一个中文字符
        if (!CHINESE_CHAR.matcher(text).find()) {
            return false;
        }
        
        return true;
    }
    

    private boolean isHeaderOrFooter(String text) {
        String trimmed = text.trim();
        
        // 页码模式：纯数字或 "第X页"
        if (trimmed.matches("^\\d+$") || trimmed.matches("^第\\d+页$")) {
            return true;
        }
        
        // 日期模式：YYYY-MM-DD 或 YYYY/MM/DD
        if (trimmed.matches("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}$")) {
            return true;
        }
        
        // 过短的文本（可能是页眉页脚）
        if (trimmed.length() < 5) {
            return true;
        }
        
        return false;
    }

    @Data
    public static class TextSegment {
        private String text;          // 文本内容
        private int pageNumber;       // 页码
        private int length;           // 文本长度
    }
}
