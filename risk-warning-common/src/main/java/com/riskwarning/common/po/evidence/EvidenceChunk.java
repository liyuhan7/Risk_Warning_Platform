package com.riskwarning.common.po.evidence;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Locale;

/** 文档级原文证据，稳定 ID 只由来源文档、位置和原文哈希决定。 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "t_evidence_chunk")
public class EvidenceChunk {

    public static final String SCHEMA_VERSION = "1.0";

    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "schema_version", length = 8, nullable = false, updatable = false)
    private String schemaVersion;

    @Column(name = "source_document_id", nullable = false, updatable = false)
    private Long sourceDocumentId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "assessment_id", nullable = false, updatable = false)
    private Long assessmentId;

    @Column(name = "source_file_name", nullable = false, updatable = false)
    private String sourceFileName;

    @Column(name = "page_number", updatable = false)
    private Integer pageNumber;

    @Column(name = "segment_index", nullable = false, updatable = false)
    private Integer segmentIndex;

    @Column(name = "char_start", updatable = false)
    private Integer charStart;

    @Column(name = "char_end", updatable = false)
    private Integer charEnd;

    @Column(name = "text", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String text;

    @Column(name = "text_hash", length = 64, nullable = false, updatable = false)
    private String textHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static EvidenceChunk create(Long sourceDocumentId, Long projectId, Long assessmentId,
                                       String sourceFileName, Integer pageNumber, Integer segmentIndex,
                                       Integer charStart, Integer charEnd, String text,
                                       LocalDateTime createdAt) {
        validateCoordinates(sourceDocumentId, projectId, assessmentId, sourceFileName,
                pageNumber, segmentIndex, charStart, charEnd, text, createdAt);
        EvidenceChunk chunk = new EvidenceChunk();
        chunk.schemaVersion = SCHEMA_VERSION;
        chunk.sourceDocumentId = sourceDocumentId;
        chunk.projectId = projectId;
        chunk.assessmentId = assessmentId;
        chunk.sourceFileName = sourceFileName;
        chunk.pageNumber = pageNumber;
        chunk.segmentIndex = segmentIndex;
        chunk.charStart = charStart;
        chunk.charEnd = charEnd;
        chunk.text = text;
        chunk.textHash = sha256(normalizeText(text));
        chunk.id = stableId(sourceDocumentId, pageNumber, segmentIndex, chunk.textHash);
        chunk.createdAt = createdAt;
        return chunk;
    }

    /** 校验从持久化层读出的证据未被篡改。 */
    public void verifyIntegrity() {
        validateCoordinates(sourceDocumentId, projectId, assessmentId, sourceFileName,
                pageNumber, segmentIndex, charStart, charEnd, text, createdAt);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !sha256(normalizeText(text)).equals(textHash)
                || !stableId(sourceDocumentId, pageNumber, segmentIndex, textHash).equals(id)) {
            throw new IllegalStateException("EvidenceChunk 完整性校验失败");
        }
    }

    public static String normalizeText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("证据原文不能为空");
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .trim().replaceAll("\\s+", " ");
    }

    public static String stableId(Long sourceDocumentId, Integer pageNumber,
                                  Integer segmentIndex, String textHash) {
        String pageValue = pageNumber == null ? "" : String.valueOf(pageNumber);
        return sha256(sourceDocumentId + "|" + pageValue + "|" + segmentIndex + "|" + textHash)
                .substring(0, 32);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static void validateCoordinates(Long sourceDocumentId, Long projectId, Long assessmentId,
                                            String sourceFileName, Integer pageNumber, Integer segmentIndex,
                                            Integer charStart, Integer charEnd, String text,
                                            LocalDateTime createdAt) {
        if (sourceDocumentId == null || sourceDocumentId <= 0 || projectId == null || projectId <= 0
                || assessmentId == null || assessmentId <= 0 || sourceFileName == null
                || sourceFileName.trim().isEmpty() || segmentIndex == null || segmentIndex < 0
                || pageNumber != null && pageNumber <= 0 || createdAt == null
                || normalizeText(text).isEmpty()) {
            throw new IllegalArgumentException("EvidenceChunk 字段不合法");
        }
        if ((charStart == null) != (charEnd == null)
                || charStart != null && (charStart < 0 || charEnd <= charStart)) {
            throw new IllegalArgumentException("证据字符位置不合法");
        }
    }
}
