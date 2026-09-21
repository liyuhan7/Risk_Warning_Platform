-- R1-03 可靠性测试隔离库初始化脚本；幂等可重复执行。
-- 只包含集成测试所需的正式表结构与真正参与断言的约束；
-- 跨表外键省略，测试数据由各测试用唯一 ID 自洽构造并自行清理。
-- 该文件不替代正式迁移，也不写回 documents/schema.sql。

-- 枚举类型与正式 schema 保持一致（存在则跳过）。
DO $$
BEGIN
    CREATE TYPE assessment_status_enum AS ENUM ('待评估', '评估中', '已完成', '评估失败');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
    CREATE TYPE indicator_risk_status_enum AS ENUM ('未评估', '已评估');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 评估结果摘要表（列定义与 Assessment 实体映射一致，overall_risk_level 为 VARCHAR）。
CREATE TABLE IF NOT EXISTS public.t_assessment_result (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    source_task_id VARCHAR(160),
    assessment_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    overall_score NUMERIC,
    overall_risk_level VARCHAR(64),
    details JSONB,
    recommendations TEXT,
    status assessment_status_enum,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_assessment_id_project
    ON public.t_assessment_result(id, project_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_assessment_source_task
    ON public.t_assessment_result(source_task_id);

-- 独立分析运行（状态与当前实体一致，包含 COMPLETED_WITHOUT_DECISION）。
CREATE TABLE IF NOT EXISTS public.t_analysis_run (
    analysis_run_id VARCHAR(64) PRIMARY KEY,
    assessment_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL CONSTRAINT ck_analysis_run_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'COMPLETED_WITHOUT_DECISION', 'FAILED')),
    started_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITHOUT TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_analysis_run_assessment FOREIGN KEY (assessment_id, project_id)
        REFERENCES public.t_assessment_result (id, project_id),
    CONSTRAINT ck_analysis_run_time CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'COMPLETED_WITHOUT_DECISION', 'FAILED')
            AND finished_at IS NOT NULL AND finished_at >= started_at))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_analysis_run_scope
    ON public.t_analysis_run(analysis_run_id, assessment_id, project_id);

-- 项目上传文件（列定义与 ProjectFile 实体映射一致；file_path 为长路径，用 TEXT）。
CREATE TABLE IF NOT EXISTS public.t_project_file (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT,
    user_id BIGINT,
    assessment_id BIGINT,
    file_path TEXT,
    original_file_name VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_project_file_project_id
    ON public.t_project_file(project_id);

-- 指标计算结果（列定义与 IndicatorResult 实体映射一致；无 t_project 外键）。
CREATE TABLE IF NOT EXISTS public.t_indicator_result (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    analysis_run_id VARCHAR(64),
    indicator_es_id TEXT NOT NULL,
    indicator_name TEXT NOT NULL,
    indicator_level INTEGER NOT NULL,
    dimension TEXT,
    "type" TEXT,
    calculated_score NUMERIC NOT NULL,
    max_possible_score NUMERIC NOT NULL DEFAULT 100,
    used_calculation_rule_type TEXT NOT NULL,
    calculation_details JSONB,
    matched_behaviors_ids TEXT[],
    risk_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    risk_status indicator_risk_status_enum NOT NULL DEFAULT '未评估',
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS ix_indicator_result_assessment
    ON public.t_indicator_result(assessment_id, analysis_run_id);

-- P2 合规分析权威结果（保留身份唯一约束；外键省略）。
CREATE TABLE IF NOT EXISTS public.t_analysis_result (
    id VARCHAR(64) PRIMARY KEY,
    schema_version VARCHAR(8) NOT NULL CHECK (schema_version = '1.0'),
    analysis_run_id VARCHAR(64) NOT NULL,
    assessment_id BIGINT NOT NULL,
    behavior_id VARCHAR(128) NOT NULL,
    evidence_ids JSONB NOT NULL CHECK (jsonb_typeof(evidence_ids) = 'array'),
    indicator_id VARCHAR(128) NOT NULL,
    regulation_ids JSONB CHECK (regulation_ids IS NULL OR jsonb_typeof(regulation_ids) = 'array'),
    applicable VARCHAR(32) NOT NULL CHECK (applicable IN ('APPLICABLE', 'NOT_APPLICABLE', 'UNKNOWN')),
    requirement TEXT,
    enterprise_fact TEXT NOT NULL,
    compliance_status VARCHAR(32) NOT NULL CHECK (
        compliance_status IN ('COMPLIANT', 'NON_COMPLIANT', 'INSUFFICIENT_EVIDENCE', 'NEEDS_REVIEW')),
    gap_type VARCHAR(32) CHECK (gap_type IS NULL OR gap_type IN (
        'MISSING_ACTION', 'PROHIBITED_ACTION', 'QUANTITATIVE_SHORTFALL',
        'QUANTITATIVE_EXCESS', 'DOCUMENTATION_GAP')),
    gap_value DOUBLE PRECISION,
    gap_unit TEXT,
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    reasoning TEXT NOT NULL,
    model_version TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    analyzed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_analysis_result_run_behavior_indicator
        UNIQUE (analysis_run_id, behavior_id, indicator_id)
);

-- Lease 模式持久任务表（与 009_durable_work.sql 一致）。
CREATE TABLE IF NOT EXISTS public.t_durable_work (
    id VARCHAR(64) PRIMARY KEY,
    namespace VARCHAR(64) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    task_key VARCHAR(160) NOT NULL,
    payload TEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    lease_until TIMESTAMP WITHOUT TIME ZONE,
    worker_id VARCHAR(128),
    lease_token VARCHAR(64),
    last_error TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_durable_work_task UNIQUE (namespace, kind, task_key),
    CONSTRAINT ck_durable_work_state CHECK (state IN ('READY', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_durable_work_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_durable_work_lease CHECK (
        (state = 'RUNNING' AND lease_until IS NOT NULL
            AND worker_id IS NOT NULL AND lease_token IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_until IS NULL
            AND worker_id IS NULL AND lease_token IS NULL))
);
-- 已存在的隔离库可能由旧脚本创建，补齐列并升级约束。
ALTER TABLE public.t_durable_work ADD COLUMN IF NOT EXISTS lease_token VARCHAR(64);
UPDATE public.t_durable_work
SET state = 'READY', worker_id = NULL, lease_token = NULL, lease_until = NULL,
    available_at = LOCALTIMESTAMP, updated_at = LOCALTIMESTAMP
WHERE state = 'RUNNING' AND lease_token IS NULL;
ALTER TABLE public.t_durable_work DROP CONSTRAINT IF EXISTS ck_durable_work_lease;
ALTER TABLE public.t_durable_work ADD CONSTRAINT ck_durable_work_lease CHECK (
    (state = 'RUNNING' AND lease_until IS NOT NULL
        AND worker_id IS NOT NULL AND lease_token IS NOT NULL)
    OR (state <> 'RUNNING' AND lease_until IS NULL
        AND worker_id IS NULL AND lease_token IS NULL));
CREATE INDEX IF NOT EXISTS ix_durable_work_claim
    ON public.t_durable_work(namespace, kind, state, available_at, lease_until);

-- 检索审计（列定义与 RetrievalAudit 实体映射一致；状态为字符串枚举）。
CREATE TABLE IF NOT EXISTS public.t_retrieval_audit (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    analysis_run_id VARCHAR(64) NOT NULL,
    behavior_id VARCHAR(128) NOT NULL,
    query_text TEXT NOT NULL,
    query_template_version VARCHAR(64) NOT NULL,
    filter_version VARCHAR(64) NOT NULL,
    filter_enabled BOOLEAN NOT NULL,
    filter_applied BOOLEAN NOT NULL,
    filter_expression TEXT NOT NULL,
    embedding_model VARCHAR(255),
    embedding_version VARCHAR(255),
    retrieval_status VARCHAR(32) NOT NULL,
    analysis_status VARCHAR(32) NOT NULL,
    candidates JSONB,
    error_code VARCHAR(255),
    error_type VARCHAR(255),
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_retrieval_audit_run_behavior UNIQUE (analysis_run_id, behavior_id)
);
