-- 独立运行记录基础；在业务入口接线前执行，不能依赖 Hibernate 自动建表代替本脚本。
-- 本脚本不更改既有评估与结果；不推断或回填历史运行。
BEGIN;

-- 组合外键保证运行中的项目与评估实际归属一致。
ALTER TABLE public.t_assessment_result
    ADD CONSTRAINT uq_assessment_id_project UNIQUE (id, project_id);

CREATE TABLE public.t_analysis_run (
    analysis_run_id VARCHAR(64) PRIMARY KEY,
    assessment_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITHOUT TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_analysis_run_assessment FOREIGN KEY (assessment_id, project_id)
        REFERENCES public.t_assessment_result (id, project_id),
    CONSTRAINT ck_analysis_run_id CHECK (length(btrim(analysis_run_id)) > 0
        AND analysis_run_id = btrim(analysis_run_id)),
    CONSTRAINT ck_analysis_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_analysis_run_time CHECK (
        (status = 'RUNNING' AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED') AND finished_at IS NOT NULL
            AND finished_at >= started_at))
);

CREATE UNIQUE INDEX uq_analysis_run_running_assessment
    ON public.t_analysis_run (assessment_id) WHERE status = 'RUNNING';
CREATE INDEX ix_analysis_run_assessment ON public.t_analysis_run (assessment_id);

COMMENT ON TABLE public.t_analysis_run IS '独立分析运行记录，评估与运行一对多';
COMMIT;
