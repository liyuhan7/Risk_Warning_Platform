package com.riskwarning.processing.util;

import com.riskwarning.processing.util.FileScanner.ScannedDocument;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ContentExtractor {

    // 最小片段长度（字符数）
    private static final int MIN_SEGMENT_LENGTH = 10;
    
    // 最大片段长度（字符数）
    private static final int MAX_SEGMENT_LENGTH = 500;

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
            List<TextSegment> pageSegments = extractFromPage(page);
            allSegments.addAll(pageSegments);
        }
        
        return allSegments;
    }


    private List<TextSegment> extractFromPage(FileScanner.PageContent page) {
        String text = page.getText();
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        // 分段处理
        List<TextSegment> segments = splitIntoSegments(text, page.getPageNumber());
        
        // 过滤无意义内容
        return segments.stream()
                .filter(this::isMeaningful)
                .collect(Collectors.toList());
    }
    

    private List<TextSegment> splitIntoSegments(String text, int pageNumber) {
        List<TextSegment> segments = new ArrayList<>();
        
        // 先按段落分割（双换行）
        String[] paragraphs = text.split("\n\n+");
        
        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            
            // 如果段落较长，按句子分割
            if (paragraph.length() > MAX_SEGMENT_LENGTH) {
                String[] sentences = splitIntoSentences(paragraph);
                for (String sentence : sentences) {
                    sentence = sentence.trim();
                    if (sentence.length() >= MIN_SEGMENT_LENGTH) {
                        segments.add(createSegment(sentence, pageNumber));
                    }
                }
            } else {
                // 段落长度合适，直接作为一个片段
                if (paragraph.length() >= MIN_SEGMENT_LENGTH) {
                    segments.add(createSegment(paragraph, pageNumber));
                }
            }
        }
        
        return segments;
    }
    

    private String[] splitIntoSentences(String text) {
        // 按句号、问号、感叹号、分号分割
        return text.split("[。！？；\n]");
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
