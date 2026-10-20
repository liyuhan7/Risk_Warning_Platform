-- Supabase/PostgreSQL Schema for 风险合规预警系统
-- 当前开发基线：在空数据库中一次创建全部表、约束和索引。

-- Definding ENUM types for better data integrity

-- 企业级角色
CREATE TYPE enterprise_role AS ENUM ('admin', 'member');
-- 项目级角色
CREATE TYPE project_role AS ENUM ('project_admin', 'editor', 'viewer');
-- 项目状态
CREATE TYPE project_status AS ENUM ('进行中', '已完成', '已归档');
-- 项目类型
CREATE TYPE project_type AS ENUM ('常规评估', '专项评估');
-- 行业枚举
CREATE TYPE industry_enum AS ENUM ('供应链管理', '市场营销与广告', '人力资源与劳动关系', '跨境交易与支付', '数据隐私与网络安全', '反垄断与不正当竞争', '知识产权', '财务与税务');
-- 地域枚举
CREATE TYPE region_enum AS ENUM ('中国', '欧盟', '美国', '多地');
-- 项目面向用户
CREATE TYPE project_oriented_user_enum AS ENUM ('政府机构与官员', '国有企业', '关键供应商/承包商', '客户', '员工', '个人用户', '公众');
-- 事件类型
CREATE TYPE event_type_enum AS ENUM ('BEHAVIOR_INGESTION', 'INDICATOR_CALCULATION', 'RISK_TRIGGERED', 'ASSESSMENT_COMPLETED', 'SYSTEM_NOTIFICATION');
-- 评估状态
CREATE TYPE assessment_status_enum AS ENUM ('待评估', '评估中', '已完成', '评估失败');
-- 指标是否进行风险评估
CREATE TYPE indicator_risk_status_enum AS ENUM ('未评估', '已评估');


-- 1. 用户表 (t_user)
-- 存储系统用户的基本信息。
CREATE TABLE IF NOT EXISTS public.t_user (
    id BIGSERIAL PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    password TEXT NOT NULL, -- 添加缺失的密码字段
    full_name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_user IS '用户信息表';
COMMENT ON COLUMN public.t_user.email IS '用户邮箱，唯一标识';
COMMENT ON COLUMN public.t_user.full_name IS '用户全名';


-- 2. 企业表 (t_enterprise)
-- 存储接受合规评估的企业基本信息。
CREATE TABLE IF NOT EXISTS public.t_enterprise (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    credit_code TEXT UNIQUE NOT NULL,
    -- 建议值: '有限责任公司', '股份有限公司', '外商投资企业' 等
    "type" TEXT,
    -- 建议值: '金融', '制造', '科技', '医疗' 等
    industry TEXT,
    business_scope TEXT,
    registered_capital NUMERIC,
    establishment_date DATE,
    legal_representative TEXT,
    registered_address TEXT,
    business_status TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_enterprise IS '企业基础信息表';
COMMENT ON COLUMN public.t_enterprise.name IS '企业名称';
COMMENT ON COLUMN public.t_enterprise.credit_code IS '统一社会信用代码，唯一';
COMMENT ON COLUMN public.t_enterprise.type IS '企业类型, 建议值: ''有限责任公司'', ''股份有限公司''';
COMMENT ON COLUMN public.t_enterprise.industry IS '行业分类, 建议值: ''金融'', ''制造'', ''科技''';


-- 3. 企业-用户关联表 (t_enterprise_user)
-- 建立用户和企业的多对多关系，并定义用户在企业中的角色。
CREATE TABLE IF NOT EXISTS public.t_enterprise_user (
    enterprise_id BIGINT NOT NULL REFERENCES public.t_enterprise(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES public.t_user(id) ON DELETE CASCADE,
    "role" enterprise_role NOT NULL DEFAULT 'member',
    PRIMARY KEY (enterprise_id, user_id)
);

COMMENT ON TABLE public.t_enterprise_user IS '企业与用户的关联表';
COMMENT ON COLUMN public.t_enterprise_user.role IS '用户在企业中的角色';


-- 4. 合规项目表 (t_project)
-- 存储企业发起的合规评估项目。
CREATE TABLE IF NOT EXISTS public.t_project (
    id BIGSERIAL PRIMARY KEY,
    enterprise_id BIGINT NOT NULL REFERENCES public.t_enterprise(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    "type" project_type,
    status project_status NOT NULL DEFAULT '进行中',
    description TEXT,
    start_date DATE,
    planned_completion_date DATE,
    actual_completion_date DATE,
    industry industry_enum,
    region region_enum,
    oriented_user project_oriented_user_enum,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_project IS '合规评估项目表';
COMMENT ON COLUMN public.t_project.enterprise_id IS '关联的企业ID';
COMMENT ON COLUMN public.t_project.status IS '项目状态';
COMMENT ON COLUMN public.t_project.type IS '项目类型';
COMMENT ON COLUMN public.t_project.industry IS '项目涉及行业';
COMMENT ON COLUMN public.t_project.region IS '项目涉及地域';
COMMENT ON COLUMN public.t_project.oriented_user IS '项目面向的用户对象';


-- 5. 项目成员表 (t_project_member)
-- 定义用户在特定项目中的角色和权限。
CREATE TABLE IF NOT EXISTS public.t_project_member (
    project_id BIGINT NOT NULL REFERENCES public.t_project(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES public.t_user(id) ON DELETE CASCADE,
    "role" project_role NOT NULL DEFAULT 'viewer',
    PRIMARY KEY (project_id, user_id)
);

COMMENT ON TABLE public.t_project_member IS '项目成员及权限表';
COMMENT ON COLUMN public.t_project_member.role IS '用户在项目中的角色';


-- 6. 评估结果表 (t_assessment_result)
-- 存储每个项目的最终评估结果摘要。
CREATE TABLE IF NOT EXISTS public.t_assessment_result (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES public.t_project(id) ON DELETE CASCADE,
    source_task_id VARCHAR(160),
    assessment_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    overall_score NUMERIC,
    overall_risk_level VARCHAR(64),
    details JSONB,
    recommendations TEXT,
    status assessment_status_enum,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_assessment_result IS '项目评估结果摘要表';
COMMENT ON COLUMN public.t_assessment_result.project_id IS '关联的项目ID';
COMMENT ON COLUMN public.t_assessment_result.source_task_id IS '创建本评估的上传确认稳定任务标识；历史记录为空';
COMMENT ON COLUMN public.t_assessment_result.overall_score IS '综合得分';
COMMENT ON COLUMN public.t_assessment_result.details IS '存储评估的详细数据，如各维度得分、风险列表等';
COMMENT ON COLUMN public.t_assessment_result.status IS '评估状态';

CREATE UNIQUE INDEX IF NOT EXISTS uq_assessment_id_project
ON public.t_assessment_result(id, project_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_assessment_source_task
ON public.t_assessment_result(source_task_id);

-- 独立分析运行及创建时声明的处理模式
CREATE TABLE IF NOT EXISTS public.t_analysis_run (
    analysis_run_id VARCHAR(64) PRIMARY KEY,
    assessment_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL CONSTRAINT ck_analysis_run_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'COMPLETED_WITHOUT_DECISION', 'FAILED')),
    execution_mode VARCHAR(16) NOT NULL DEFAULT 'REASONING' CONSTRAINT ck_analysis_run_mode CHECK (
        execution_mode = 'REASONING'),
    expected_behavior_ids JSONB,
    behavior_input_hash VARCHAR(64) CONSTRAINT ck_analysis_run_behavior_hash CHECK (
        behavior_input_hash IS NULL OR behavior_input_hash ~ '^[a-f0-9]{64}$'),
    analysis_summary JSONB,
    CONSTRAINT ck_analysis_run_analysis_summary CHECK (analysis_summary IS NULL OR
        (execution_mode IS NOT NULL AND execution_mode = 'REASONING' AND jsonb_typeof(analysis_summary) = 'object')),
    CONSTRAINT ck_analysis_run_behaviors CHECK (expected_behavior_ids IS NULL OR
        (execution_mode IS NOT NULL AND execution_mode = 'REASONING'
         AND jsonb_typeof(expected_behavior_ids) = 'array'
         AND expected_behavior_ids <> '[]'::jsonb)),
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
CREATE UNIQUE INDEX IF NOT EXISTS uq_analysis_run_assessment
ON public.t_analysis_run(analysis_run_id, assessment_id);




-- 7. 指标计算结果表 (t_indicator_result)
-- 存储每次评估中指标的具体计算结果，与ES中的静态指标定义关联
CREATE TABLE IF NOT EXISTS public.t_indicator_result (
    id BIGSERIAL PRIMARY KEY,
    -- 关联的项目ID
    project_id BIGINT NOT NULL REFERENCES public.t_project(id) ON DELETE CASCADE,
    -- 关联的评估ID
    assessment_id BIGINT NOT NULL REFERENCES public.t_assessment_result(id) ON DELETE CASCADE,
    -- 产生本结果的独立分析运行；历史记录允许为空
    analysis_run_id VARCHAR(64),
    -- ES中指标的ID
    indicator_es_id TEXT NOT NULL,
    -- 指标名称（冗余存储，便于查询）
    indicator_name TEXT NOT NULL,
    -- 指标层级
    indicator_level INTEGER NOT NULL,
    -- 指标维度
    dimension TEXT,
    -- 指标类型
    "type" TEXT,
    -- 计算得分
    calculated_score NUMERIC NOT NULL,
    -- 最大可能得分
    max_possible_score NUMERIC NOT NULL DEFAULT 100,
    -- 使用的计算规则类型
    used_calculation_rule_type TEXT NOT NULL,
    -- 计算详情（存储具体的计算过程和使用的规则）
    calculation_details JSONB,
    -- 匹配到的行为数据
    matched_behaviors_ids TEXT[],
    -- 是否触发了风险规则
    risk_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    risk_level VARCHAR(32) CHECK (risk_level IS NULL OR risk_level IN ('NO_RISK', 'LOW_RISK', 'MEDIUM_RISK', 'HIGH_RISK')),
    -- 风险是否已评估
    risk_status indicator_risk_status_enum NOT NULL DEFAULT '未评估',
    -- 计算时间
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_indicator_result IS '指标计算结果表';
COMMENT ON COLUMN public.t_indicator_result.project_id IS '关联的项目ID';
COMMENT ON COLUMN public.t_indicator_result.assessment_id IS '关联的评估结果ID';
COMMENT ON COLUMN public.t_indicator_result.analysis_run_id IS '产生本指标结果的独立分析运行；历史记录为空';
COMMENT ON COLUMN public.t_indicator_result.indicator_es_id IS 'Elasticsearch中指标的ID';
COMMENT ON COLUMN public.t_indicator_result.indicator_name IS '指标名称（冗余存储）';
COMMENT ON COLUMN public.t_indicator_result.calculated_score IS '计算得到的分数';
COMMENT ON COLUMN public.t_indicator_result.used_calculation_rule_type IS '使用的计算规则类型：binary, range';
COMMENT ON COLUMN public.t_indicator_result.calculation_details IS '计算详情，包含具体规则和中间过程';
COMMENT ON COLUMN public.t_indicator_result.matched_behaviors_ids IS '匹配到的行为数据ES ID列表';
COMMENT ON COLUMN public.t_indicator_result.risk_triggered IS '是否触发了风险规则';

-- P2 合规分析权威结果
CREATE TABLE IF NOT EXISTS public.t_analysis_result (
    id VARCHAR(64) PRIMARY KEY,
    schema_version VARCHAR(8) NOT NULL CONSTRAINT ck_analysis_result_schema_version CHECK (schema_version IN ('1.0', '1.1', '1.2')),
    analysis_run_id VARCHAR(64) NOT NULL REFERENCES public.t_analysis_run(analysis_run_id),
    assessment_id BIGINT NOT NULL REFERENCES public.t_assessment_result(id) ON DELETE CASCADE,
    behavior_id VARCHAR(128) NOT NULL,
    evidence_ids JSONB NOT NULL CHECK (jsonb_typeof(evidence_ids) = 'array'),
    indicator_id VARCHAR(128) NOT NULL,
    requirement_findings JSONB,
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
    condition_met BOOLEAN,
    observed_value DOUBLE PRECISION,
    observed_unit TEXT,
    confidence DOUBLE PRECISION NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    reasoning TEXT NOT NULL,
    model_version TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    analyzed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_analysis_result_run_behavior_indicator UNIQUE (analysis_run_id, behavior_id, indicator_id),
    CONSTRAINT fk_analysis_result_run_scope FOREIGN KEY (analysis_run_id, assessment_id)
        REFERENCES public.t_analysis_run(analysis_run_id, assessment_id),
    CONSTRAINT ck_analysis_result_bridge CHECK (
        schema_version = '1.0' OR (
            (observed_value IS NULL OR (observed_unit IS NOT NULL AND btrim(observed_unit) <> ''
                AND observed_value > '-Infinity'::float8 AND observed_value < 'Infinity'::float8))
            AND (condition_met IS NULL OR (observed_value IS NULL AND observed_unit IS NULL))
            AND (observed_unit IS NULL OR observed_value IS NOT NULL))),
    CONSTRAINT ck_analysis_result_unknown CHECK (
        schema_version = '1.0' OR applicable <> 'UNKNOWN'
        OR compliance_status IN ('INSUFFICIENT_EVIDENCE', 'NEEDS_REVIEW')),
    CONSTRAINT ck_analysis_result_gap CHECK (
        schema_version = '1.2' OR ((compliance_status <> 'NON_COMPLIANT' OR gap_type IS NOT NULL)
        AND (gap_value IS NULL OR gap_unit IS NOT NULL)))
);

-- 模型调用审计不保存完整 Prompt，重试序号按 Run/Behavior 分配。
CREATE TABLE IF NOT EXISTS public.t_analysis_attempt (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    analysis_run_id VARCHAR(64) NOT NULL,
    behavior_id VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    context_hash VARCHAR(64) NOT NULL,
    model_version TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    raw_response TEXT,
    validation_errors JSONB CHECK (validation_errors IS NULL OR jsonb_typeof(validation_errors) = 'array'),
    status VARCHAR(32) NOT NULL CHECK (status IN ('VALID', 'INVALID', 'PROVIDER_FAILED')),
    error_code TEXT,
    error_message TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_analysis_attempt UNIQUE (analysis_run_id, behavior_id, attempt_no),
    CONSTRAINT fk_analysis_attempt_scope FOREIGN KEY (analysis_run_id, assessment_id, project_id)
        REFERENCES public.t_analysis_run(analysis_run_id, assessment_id, project_id)
);

-- 规则未登记或输入无效时，版本及计算详情允许为空，错误原因仍须记录。
CREATE TABLE IF NOT EXISTS public.t_rule_evaluation_audit (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    analysis_run_id VARCHAR(64) NOT NULL,
    indicator_id VARCHAR(128) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    rule_version TEXT,
    threshold_version TEXT,
    aggregation_operator TEXT,
    status VARCHAR(32) NOT NULL CHECK (status IN (
        'DECIDED', 'DECIDED_WITH_CONFLICT', 'UNSUPPORTED_RULE', 'INVALID_INPUT', 'NEEDS_REVIEW')),
    calculation_details JSONB CHECK (calculation_details IS NULL OR jsonb_typeof(calculation_details) = 'object'),
    conflict BOOLEAN NOT NULL,
    error_code TEXT,
    error_message TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_rule_evaluation_audit UNIQUE (analysis_run_id, indicator_id),
    CONSTRAINT fk_rule_evaluation_audit_scope FOREIGN KEY (analysis_run_id, assessment_id, project_id)
        REFERENCES public.t_analysis_run(analysis_run_id, assessment_id, project_id)
);
CREATE INDEX IF NOT EXISTS ix_analysis_attempt_scope
ON public.t_analysis_attempt(assessment_id, analysis_run_id);
CREATE INDEX IF NOT EXISTS ix_rule_evaluation_audit_scope
ON public.t_rule_evaluation_audit(assessment_id, analysis_run_id);

-- P2 检索与固定分析逐 Behavior 审计
CREATE TABLE IF NOT EXISTS public.t_retrieval_audit (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL REFERENCES public.t_assessment_result(id) ON DELETE CASCADE,
    analysis_run_id VARCHAR(64) NOT NULL REFERENCES public.t_analysis_run(analysis_run_id),
    behavior_id VARCHAR(128) NOT NULL,
    query_text TEXT NOT NULL,
    query_template_version TEXT NOT NULL,
    filter_version TEXT NOT NULL,
    filter_enabled BOOLEAN NOT NULL,
    filter_applied BOOLEAN NOT NULL,
    filter_expression TEXT NOT NULL,
    embedding_model TEXT,
    embedding_version TEXT,
    retrieval_status VARCHAR(32) NOT NULL CHECK (
        retrieval_status IN ('SUCCESS', 'NO_CANDIDATES', 'FAILED', 'SNAPSHOT_UNAVAILABLE')),
    analysis_status VARCHAR(32) NOT NULL CONSTRAINT ck_retrieval_audit_analysis_status CHECK (
        analysis_status IN ('NOT_ATTEMPTED', 'SUCCESS', 'RECALL_GAP', 'INSUFFICIENT_EVIDENCE', 'NOT_DEMO_INPUT', 'WAITING_ANALYSIS',
            'ANALYZED', 'NO_APPLICABLE_CANDIDATE', 'NEEDS_REVIEW')),
    candidates JSONB CHECK (candidates IS NULL OR jsonb_typeof(candidates) = 'array'),
    error_code TEXT,
    error_type TEXT,
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_retrieval_audit_run_behavior UNIQUE (analysis_run_id, behavior_id),
    CONSTRAINT fk_retrieval_audit_run_scope FOREIGN KEY (analysis_run_id, assessment_id, project_id)
        REFERENCES public.t_analysis_run(analysis_run_id, assessment_id, project_id)
);

CREATE INDEX IF NOT EXISTS ix_analysis_result_assessment_run
ON public.t_analysis_result(assessment_id, analysis_run_id);
CREATE INDEX IF NOT EXISTS ix_retrieval_audit_assessment_run
ON public.t_retrieval_audit(assessment_id, analysis_run_id);

-- 8. 事件表 (t_event)
-- 用于记录系统中的重要事件或日志，如风险预警、评估完成等。
CREATE TABLE IF NOT EXISTS public.t_event (
    id BIGSERIAL PRIMARY KEY,
    -- 事件类型
    event_type event_type_enum NOT NULL,
    -- 事件发起者
    user_id BIGINT REFERENCES public.t_user(id) ON DELETE SET NULL,
    -- 事件关联的项目
    project_id BIGINT REFERENCES public.t_project(id) ON DELETE CASCADE,
    -- 事件相关的详细数据，结构因 event_type 而异
    detail_data JSONB,
    -- 事件发生时间
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_event IS '系统事件与日志表';
COMMENT ON COLUMN public.t_event.event_type IS '事件的类型';
COMMENT ON COLUMN public.t_event.user_id IS '事件的发起者（如果适用）';
COMMENT ON COLUMN public.t_event.project_id IS '事件所属的项目（如果适用）';
COMMENT ON COLUMN public.t_event.detail_data IS '与事件相关的上下文数据';

-- 创建触发器函数，用于在更新时自动修改 updated_at 时间戳
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 为需要自动更新时间戳的表创建触发器
CREATE TRIGGER update_user_updated_at
BEFORE UPDATE ON public.t_user
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_enterprise_updated_at
BEFORE UPDATE ON public.t_enterprise
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_project_updated_at
BEFORE UPDATE ON public.t_project
FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

-- 创建索引以提高查询性能

-- 指标计算结果表索引
CREATE INDEX IF NOT EXISTS idx_indicator_result_project_assessment 
ON public.t_indicator_result(project_id, assessment_id);

CREATE INDEX IF NOT EXISTS idx_indicator_result_calculated_at 
ON public.t_indicator_result(calculated_at);

-- 事件表索引
CREATE INDEX IF NOT EXISTS idx_event_project_type 
ON public.t_event(project_id, event_type);

CREATE INDEX IF NOT EXISTS idx_event_created_at 
ON public.t_event(created_at);

-- 评估结果表索引
CREATE INDEX IF NOT EXISTS idx_assessment_result_project_date 
ON public.t_assessment_result(project_id, assessment_date);

-- 添加约束
-- 确保指标计算结果的分数不为负数
ALTER TABLE public.t_indicator_result 
ADD CONSTRAINT chk_calculated_score_non_negative 
CHECK (calculated_score >= 0);

-- 确保最大可能得分为正数
ALTER TABLE public.t_indicator_result 
ADD CONSTRAINT chk_max_possible_score_positive 
CHECK (max_possible_score > 0);

-- 确保最大可能得分不超过最大可能得分
ALTER TABLE public.t_indicator_result 
ADD CONSTRAINT chk_calculated_score_within_max 
CHECK (calculated_score <= max_possible_score);

-- 9. 项目文件表 (t_project_file)
-- 存储合规项目中上传证明文件的记录。
CREATE TABLE IF NOT EXISTS public.t_project_file (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT REFERENCES public.t_project(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES public.t_user(id) ON DELETE SET NULL,
    assessment_id BIGINT REFERENCES public.t_assessment_result(id) ON DELETE CASCADE,
    file_path TEXT,
    original_file_name VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_project_file_id_project_assessment UNIQUE (id, project_id, assessment_id)
);

COMMENT ON TABLE public.t_project_file IS '项目上传文件记录表，一份物理文件对应一行';
COMMENT ON COLUMN public.t_project_file.project_id IS '关联的项目ID';
COMMENT ON COLUMN public.t_project_file.user_id IS '上传者ID';
COMMENT ON COLUMN public.t_project_file.assessment_id IS '关联的评估结果ID；历史记录允许为空';
COMMENT ON COLUMN public.t_project_file.file_path IS '文件存储路径';
COMMENT ON COLUMN public.t_project_file.original_file_name IS '用户上传时的原始文件名；历史记录允许为空';

-- 项目文件表索引
CREATE INDEX IF NOT EXISTS idx_project_file_project_id 
ON public.t_project_file(project_id);

-- 文档级原文证据；以文件作用域约束阻止跨项目或跨评估引用。
CREATE TABLE IF NOT EXISTS public.t_evidence_chunk (
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
        REFERENCES public.t_project_file (id, project_id, assessment_id)
        ON DELETE RESTRICT
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_evidence_source_position
    ON public.t_evidence_chunk (source_document_id, COALESCE(page_number, -1), segment_index);
CREATE INDEX IF NOT EXISTS ix_evidence_source_document
    ON public.t_evidence_chunk (source_document_id, page_number, segment_index);
COMMENT ON TABLE public.t_evidence_chunk IS '文档级权威证据；不含分析运行身份';
COMMENT ON COLUMN public.t_evidence_chunk.assessment_id IS '文件上传所属评估，不是分析运行';

-- 10. Lease 模式持久任务表
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
            AND worker_id IS NULL AND lease_token IS NULL)
    )
);
CREATE INDEX IF NOT EXISTS ix_durable_work_claim
ON public.t_durable_work(namespace, kind, state, available_at, lease_until);

-- 行为要求评分及本次使用的依据与规则。
CREATE TABLE IF NOT EXISTS public.t_scoring_run_snapshot (
    analysis_run_id VARCHAR(64) PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    registry_version TEXT NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    snapshot_hash VARCHAR(64) NOT NULL,
    source_bundles JSONB NOT NULL,
    rule_definitions JSONB NOT NULL,
    indicator_behaviors JSONB NOT NULL,
    fact_extraction_plan JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT fk_scoring_snapshot_scope FOREIGN KEY (analysis_run_id, assessment_id, project_id)
        REFERENCES public.t_analysis_run(analysis_run_id, assessment_id, project_id)
);

CREATE TABLE IF NOT EXISTS public.t_requirement_evaluation (
    id VARCHAR(64) PRIMARY KEY,
    project_id BIGINT NOT NULL,
    assessment_id BIGINT NOT NULL,
    analysis_run_id VARCHAR(64) NOT NULL,
    indicator_id VARCHAR(128) NOT NULL,
    requirement_id VARCHAR(128) NOT NULL,
    behavior_id VARCHAR(128) NOT NULL,
    regulation_id VARCHAR(128) NOT NULL,
    judgment VARCHAR(32) NOT NULL,
    satisfaction NUMERIC,
    earned NUMERIC,
    maximum_score NUMERIC,
    observed_value NUMERIC,
    observed_unit TEXT,
    condition_met BOOLEAN,
    gap_type VARCHAR(32),
    gap_value NUMERIC,
    gap_unit TEXT,
    violation_degree TEXT,
    input_hash VARCHAR(64) NOT NULL,
    source_bundle_hash VARCHAR(64) NOT NULL,
    registry_version TEXT NOT NULL,
    rule_version TEXT NOT NULL,
    evidence_ids JSONB NOT NULL,
    conflict BOOLEAN NOT NULL,
    reason TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_requirement_evaluation UNIQUE (analysis_run_id, indicator_id, requirement_id, behavior_id),
    CONSTRAINT fk_requirement_evaluation_scope FOREIGN KEY (analysis_run_id, assessment_id, project_id)
        REFERENCES public.t_analysis_run(analysis_run_id, assessment_id, project_id),
    CONSTRAINT ck_requirement_satisfaction CHECK (satisfaction IS NULL OR satisfaction BETWEEN 0 AND 1),
    CONSTRAINT ck_requirement_score CHECK (earned IS NULL OR (maximum_score IS NOT NULL AND earned BETWEEN 0 AND maximum_score))
);
CREATE INDEX IF NOT EXISTS idx_requirement_evaluation_behavior ON public.t_requirement_evaluation(analysis_run_id, behavior_id);
CREATE INDEX IF NOT EXISTS idx_requirement_evaluation_regulation ON public.t_requirement_evaluation(analysis_run_id, regulation_id);
