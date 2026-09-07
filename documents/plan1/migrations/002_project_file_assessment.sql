-- 为 P1 新上传文件建立评估归属；历史文件保持 NULL，不能自动猜测归属。
BEGIN;

ALTER TABLE public.t_project_file
    ADD COLUMN assessment_id BIGINT;

ALTER TABLE public.t_project_file
    ADD CONSTRAINT fk_project_file_assessment_project
    FOREIGN KEY (assessment_id, project_id)
    REFERENCES public.t_assessment_result (id, project_id);

CREATE INDEX ix_project_file_assessment
    ON public.t_project_file (assessment_id);

COMMENT ON COLUMN public.t_project_file.assessment_id IS
    '新分析链的评估归属；历史记录未核实前为空';

COMMIT;
