-- Supabase/PostgreSQL Schema for 风险合规预警系统
-- Version: 1.4
-- Last Updated: 2025-11-05

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
    assessment_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    overall_score NUMERIC,
    overall_risk_level INT,
    details JSONB,
    recommendations TEXT,
    status assessment_status_enum,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_assessment_result IS '项目评估结果摘要表';
COMMENT ON COLUMN public.t_assessment_result.project_id IS '关联的项目ID';
COMMENT ON COLUMN public.t_assessment_result.overall_score IS '综合得分';
COMMENT ON COLUMN public.t_assessment_result.details IS '存储评估的详细数据，如各维度得分、风险列表等';
COMMENT ON COLUMN public.t_assessment_result.status IS '评估状态';




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
    file_paths TEXT, -- 存储JSON序列化后的文件路径列表
    create_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE public.t_project_file IS '项目上传文件记录表';
COMMENT ON COLUMN public.t_project_file.project_id IS '关联的项目ID';
COMMENT ON COLUMN public.t_project_file.user_id IS '上传者ID';
COMMENT ON COLUMN public.t_project_file.file_paths IS '存储文件存储路径的JSON列表';

-- 项目文件表索引
CREATE INDEX IF NOT EXISTS idx_project_file_project_id 
ON public.t_project_file(project_id);
