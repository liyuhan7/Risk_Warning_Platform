package com.riskwarning.processing.util;

import com.riskwarning.common.enums.FileTypeEnum;
import com.riskwarning.common.exception.BusinessException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tika.exception.TikaException;


@Slf4j
@Component
public class FileScanner {

    private final Tika tika = new Tika();

    public ScannedDocument scan(File file) {
        if (file == null || !file.exists()) {
            throw new BusinessException("文件不存在");
        }

        try {
            FileTypeEnum fileType = detectFileType(file);
            
            ScannedDocument document = new ScannedDocument();
            document.setFileName(file.getName());
            document.setFileType(fileType);
            
            switch (fileType) {
                case PDF:
                    return scanPdf(file, document);
                case WORD:
                    return scanWord(file, document);
                default:
                    // 使用Tika作为通用解析器
                    return scanWithTika(file, document);
            }
        } catch (Exception e) {
            throw new BusinessException("扫描文件失败: " + e.getMessage());
        }
    }

    public List<PageContent> scanByPage(File file) {
        if (file == null || !file.exists()) {
            throw new BusinessException("文件不存在");
        }

        try {
            FileTypeEnum fileType = detectFileType(file);
            
            switch (fileType) {
                case PDF:
                    return scanPdfByPage(file);
                case WORD:
                    // Word文档按段落分页
                    return scanWordByParagraph(file);
                default:
                    // 使用Tika提取后按固定长度分页
                    return scanWithTikaByPage(file);
            }
        } catch (Exception e) {
            throw new BusinessException("分页扫描失败: " + e.getMessage());
        }
    }

    /**
     * 获取文档结构信息
     */
    public DocumentStructure getStructure(File file) {
        ScannedDocument scanned = scan(file);
        
        DocumentStructure structure = new DocumentStructure();
        structure.setTotalPages(scanned.getPages().size());
        structure.setTotalCharacters(scanned.getFullText().length());
        structure.setMetadata(scanned.getMetadata());
        
        return structure;
    }

    /**
     * 检测文件类型
     */
    private FileTypeEnum detectFileType(File file) {
        String fileName = file.getName().toLowerCase();
        
        if (fileName.endsWith(".pdf")) {
            return FileTypeEnum.PDF;
        } else if (fileName.endsWith(".docx")) {
            return FileTypeEnum.WORD;
        } else if (fileName.endsWith(".doc")) {
            return FileTypeEnum.WORD;
        }
        
        // 使用Tika检测
        try {
            String detectedType = tika.detect(file);
            if (detectedType.contains("pdf")) {
                return FileTypeEnum.PDF;
            } else if (detectedType.contains("word") || detectedType.contains("msword")) {
                return FileTypeEnum.WORD;
            }
        } catch (IOException e) {
        }
        
        throw new BusinessException("不支持的文件类型: " + fileName);
    }


    private ScannedDocument scanPdf(File file, ScannedDocument document) throws IOException {
        try (PDDocument pdDocument = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(pdDocument);
            
            document.setFullText(fullText);
            document.setTotalPages(pdDocument.getNumberOfPages());
            
            // 分页提取
            List<PageContent> pages = new ArrayList<>();
            for (int page = 1; page <= pdDocument.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(pdDocument);
                
                PageContent pageContent = new PageContent();
                pageContent.setPageNumber(page);
                pageContent.setText(pageText);
                pages.add(pageContent);
            }
            document.setPages(pages);
            
            // 元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("title", pdDocument.getDocumentInformation().getTitle());
            metadata.put("author", pdDocument.getDocumentInformation().getAuthor());
            metadata.put("subject", pdDocument.getDocumentInformation().getSubject());
            metadata.put("creator", pdDocument.getDocumentInformation().getCreator());
            document.setMetadata(metadata);

            return document;
        }
    }


    private List<PageContent> scanPdfByPage(File file) throws IOException {
        List<PageContent> pages = new ArrayList<>();
        
        try (PDDocument pdDocument = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            
            for (int page = 1; page <= pdDocument.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(pdDocument);
                
                PageContent pageContent = new PageContent();
                pageContent.setPageNumber(page);
                pageContent.setText(pageText);
                pages.add(pageContent);
            }
        }
        
        return pages;
    }

 
    private ScannedDocument scanWord(File file, ScannedDocument scannedDoc) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument wordDocument = new XWPFDocument(fis)) {
            
            StringBuilder fullText = new StringBuilder();
            List<PageContent> pages = new ArrayList<>();
            int pageNumber = 1;
            StringBuilder currentPageText = new StringBuilder();
            
            // 提取段落
            for (XWPFParagraph paragraph : wordDocument.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    // 段落间使用双换行，与 ContentExtractor 的分段约定保持一致
                    fullText.append(text).append("\n\n");
                    currentPageText.append(text).append("\n\n");
                    
                    // 简单的分页逻辑：每5000字符一页（可根据实际情况调整）
                    if (currentPageText.length() > 5000) {
                        PageContent pageContent = new PageContent();
                        pageContent.setPageNumber(pageNumber++);
                        pageContent.setText(currentPageText.toString());
                        pages.add(pageContent);
                        currentPageText = new StringBuilder();
                    }
                }
            }
            
            // 添加最后一页
            if (currentPageText.length() > 0) {
                PageContent pageContent = new PageContent();
                pageContent.setPageNumber(pageNumber);
                pageContent.setText(currentPageText.toString());
                pages.add(pageContent);
            }
            
            scannedDoc.setFullText(fullText.toString());
            scannedDoc.setTotalPages(pages.size());
            scannedDoc.setPages(pages);
            
            // 元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("title", wordDocument.getProperties().getCoreProperties().getTitle());
            metadata.put("author", wordDocument.getProperties().getCoreProperties().getCreator());
            scannedDoc.setMetadata(metadata);
            return scannedDoc;
        }
    }


    private List<PageContent> scanWordByParagraph(File file) throws IOException {
        List<PageContent> pages = new ArrayList<>();
        
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            
            int pageNumber = 1;
            StringBuilder currentPageText = new StringBuilder();
            
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.trim().isEmpty()) {
                    // 段落间使用双换行，与 ContentExtractor 按 \n\n+ 分段的约定保持一致，
                    // 确保每个段落独立成为一条行为数据
                    currentPageText.append(text).append("\n\n");
                    
                    // 每5000字符一页
                    if (currentPageText.length() > 5000) {
                        PageContent pageContent = new PageContent();
                        pageContent.setPageNumber(pageNumber++);
                        pageContent.setText(currentPageText.toString());
                        pages.add(pageContent);
                        currentPageText = new StringBuilder();
                    }
                }
            }
            
            // 最后一页
            if (currentPageText.length() > 0) {
                PageContent pageContent = new PageContent();
                pageContent.setPageNumber(pageNumber);
                pageContent.setText(currentPageText.toString());
                pages.add(pageContent);
            }
        }
        
        return pages;
    }


    private ScannedDocument scanWithTika(File file, ScannedDocument document) throws IOException {
        Metadata metadata = new Metadata();
        String fullText;
        try (InputStream inputStream = new FileInputStream(file)) {
            fullText = tika.parseToString(inputStream, metadata);
        } catch (TikaException e) {
            throw new IOException("Tika解析文件失败: " + e.getMessage(), e);
        }
        
        document.setFullText(fullText);
        
        // 提取元数据
        Map<String, Object> metaMap = new HashMap<>();
        for (String name : metadata.names()) {
            metaMap.put(name, metadata.get(name));
        }
        document.setMetadata(metaMap);
        
        // 简单分页：每5000字符一页
        List<PageContent> pages = splitIntoPages(fullText);
        document.setPages(pages);
        document.setTotalPages(pages.size());
        
        return document;
    }


    private List<PageContent> scanWithTikaByPage(File file) throws IOException {
        String fullText;
        try (InputStream inputStream = new FileInputStream(file)) {
            fullText = tika.parseToString(inputStream);
        } catch (TikaException e) {
            throw new IOException("Tika解析文件失败: " + e.getMessage(), e);
        }
        return splitIntoPages(fullText);
    }

 
    private List<PageContent> splitIntoPages(String text) {
        List<PageContent> pages = new ArrayList<>();
        int pageSize = 5000; // 每页5000字符
        int totalLength = text.length();
        
        for (int i = 0; i < totalLength; i += pageSize) {
            int end = Math.min(i + pageSize, totalLength);
            String pageText = text.substring(i, end);
            
            PageContent pageContent = new PageContent();
            pageContent.setPageNumber(pages.size() + 1);
            pageContent.setText(pageText);
            pages.add(pageContent);
        }
        
        return pages;
    }


    @Data
    public static class ScannedDocument {
        private String fileName;
        private FileTypeEnum fileType;
        private String fullText;
        private List<PageContent> pages = new ArrayList<>();
        private int totalPages;
        private Map<String, Object> metadata = new HashMap<>();
    }

 
    @Data
    public static class PageContent {
        private int pageNumber;
        private String text;
    }

    @Data
    public static class DocumentStructure {
        private int totalPages;
        private long totalCharacters;
        private Map<String, Object> metadata;
    }
}

