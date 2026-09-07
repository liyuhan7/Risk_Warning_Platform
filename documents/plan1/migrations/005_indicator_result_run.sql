BEGIN;

ALTER TABLE public.t_indicator_result
    ADD COLUMN analysis_run_id VARCHAR(64);

ALTER TABLE public.t_indicator_result
    ADD CONSTRAINT fk_indicator_result_run FOREIGN KEY (analysis_run_id)
        REFERENCES public.t_analysis_run (analysis_run_id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_indicator_result_assessment_run_indicator
    ON public.t_indicator_result (assessment_id, analysis_run_id, indicator_es_id)
    WHERE analysis_run_id IS NOT NULL;

COMMENT ON COLUMN public.t_indicator_result.analysis_run_id
    IS '产生本指标结果的独立分析运行；历史记录为空';

COMMIT;
