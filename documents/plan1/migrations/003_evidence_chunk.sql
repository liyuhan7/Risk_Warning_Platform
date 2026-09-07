-- 仅在已执行 001_analysis_run.sql 与 002_project_file_assessment.sql 的业务库运行一次。
BEGIN;

ALTER TABLE t_project_file
    ADD CONSTRAINT uq_project_file_id_project_assessment
        UNIQUE (id, project_id, assessment_id);

CREATE TABLE t_evidence_chunk (
    id VARCHAR(32) PRIMARY KEY,
    schema_version VARCHAR(8) NOT NULL DEFAULT '1.0',
    source_document_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    source_file_name VARCHAR(512) NOT NULL,
    page_number INTEGER,
    segment_index INTEGER NOT NULL,
    char_start INTEGER,
    char_end INTEGER,
    text TEXT NOT NULL,
    text_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT ck_evidence_schema_version CHECK (schema_version = '1.0'),
    CONSTRAINT ck_evidence_id_hex CHECK (id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_evidence_text_hash_hex CHECK (text_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_evidence_page CHECK (page_number IS NULL OR page_number >= 1),
    CONSTRAINT ck_evidence_segment CHECK (segment_index >= 0),
    CONSTRAINT ck_evidence_char_range CHECK (
        (char_start IS NULL AND char_end IS NULL)
        OR (char_start IS NOT NULL AND char_end IS NOT NULL AND char_start >= 0 AND char_end > char_start)
    ),
    CONSTRAINT ck_evidence_text_not_blank CHECK (btrim(text) <> ''),
    CONSTRAINT fk_evidence_source_document_scope
        FOREIGN KEY (source_document_id, project_id, assessment_id)
        REFERENCES t_project_file (id, project_id, assessment_id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_evidence_source_position
    ON t_evidence_chunk (source_document_id, COALESCE(page_number, -1), segment_index);
CREATE INDEX ix_evidence_source_document ON t_evidence_chunk (source_document_id, page_number, segment_index);

COMMENT ON TABLE t_evidence_chunk IS '文档级权威证据；不含分析运行身份';
COMMENT ON COLUMN t_evidence_chunk.assessment_id IS '文件上传所属评估，不是分析运行';

COMMIT;
