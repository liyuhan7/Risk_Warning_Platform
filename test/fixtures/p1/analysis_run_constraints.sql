-- 仅在专用空测试数据库执行；fixture 表名与业务一致，不得用于业务数据库。
\set ON_ERROR_STOP on
CREATE TABLE public.t_assessment_result (id BIGINT PRIMARY KEY, project_id BIGINT NOT NULL);
INSERT INTO public.t_assessment_result VALUES (1, 10), (2, 10), (3, 20);
CREATE TABLE public.t_project_file (
    id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    file_path TEXT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE
);
\ir ../../../documents/local/plan1/migrations/001_analysis_run.sql
\ir ../../../documents/local/plan1/migrations/002_project_file_assessment.sql
\ir ../../../documents/local/plan1/migrations/003_evidence_chunk.sql
\ir ../../../documents/local/plan1/migrations/004_project_file_original_name.sql

INSERT INTO public.t_project_file (id, project_id, user_id, assessment_id, file_path, original_file_name)
VALUES (101, 10, 1, 1, 'assessment-a.pdf', '企业管理制度.pdf'),
       (102, 10, 1, 2, 'assessment-b.pdf', 'assessment-b.pdf'),
       (103, 10, 1, NULL, 'historical.pdf', NULL);

INSERT INTO public.t_evidence_chunk (
    id, schema_version, source_document_id, project_id, assessment_id, source_file_name,
    page_number, segment_index, text, text_hash, created_at
) VALUES (
    '74fea0d7b7f9e65ee1d4f1eff01b6bbe', '1.0', 101, 10, 1, 'assessment-a.pdf',
    2, 0, 'repository evidence', 'c2b9f62d6c55d6bbd2255a2f9d9862d4a1bcd32b76953447f4be57336ee2cb0f',
    '2026-09-05 10:00:00'
);

DO $$
BEGIN
    BEGIN
        INSERT INTO public.t_evidence_chunk (
            id, schema_version, source_document_id, project_id, assessment_id, source_file_name,
            page_number, segment_index, text, text_hash, created_at
        ) VALUES (
            'cccccccccccccccccccccccccccccccc', '1.0', 101, 10, 1, 'assessment-a.pdf',
            2, 0, 'duplicate position', 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
            '2026-09-05 10:00:00'
        );
        RAISE EXCEPTION '同一文件同一位置的证据未被拒绝';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO public.t_evidence_chunk (
            id, schema_version, source_document_id, project_id, assessment_id, source_file_name,
            page_number, segment_index, text, text_hash, created_at
        ) VALUES (
            'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', '1.0', 101, 20, 1, 'assessment-a.pdf',
            1, 1, 'wrong scope', 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
            '2026-09-05 10:00:00'
        );
        RAISE EXCEPTION '证据跨项目引用未被拒绝';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
END $$;

DO $$
BEGIN
    BEGIN
        INSERT INTO public.t_project_file (id, project_id, user_id, assessment_id, file_path)
        VALUES (104, 10, 1, 3, 'wrong-project.pdf');
        RAISE EXCEPTION '文件的评估与项目错配未被拒绝';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
END $$;

INSERT INTO public.t_analysis_run (analysis_run_id, assessment_id, project_id, status, started_at)
VALUES ('run-a1', 1, 10, 'RUNNING', '2026-09-05 10:00:00');
INSERT INTO public.t_analysis_run (analysis_run_id, assessment_id, project_id, status, started_at)
VALUES ('run-b1', 2, 10, 'RUNNING', '2026-09-05 10:00:00');

DO $$
BEGIN
    BEGIN
        INSERT INTO public.t_analysis_run (analysis_run_id, assessment_id, project_id, status, started_at)
        VALUES ('run-a2', 1, 10, 'RUNNING', '2026-09-05 11:00:00');
        RAISE EXCEPTION '同评估重复在途运行未被拒绝';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO public.t_analysis_run (analysis_run_id, assessment_id, project_id, status, started_at)
        VALUES ('wrong-project', 3, 10, 'RUNNING', '2026-09-05 11:00:00');
        RAISE EXCEPTION '评估项目错配未被拒绝';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    BEGIN
        UPDATE public.t_analysis_run SET status = 'SUCCEEDED' WHERE analysis_run_id = 'run-a1';
        RAISE EXCEPTION '缺失结束时间未被拒绝';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE public.t_analysis_run SET status = 'FAILED', finished_at = '2026-09-05 09:00:00'
        WHERE analysis_run_id = 'run-a1';
        RAISE EXCEPTION '结束时间早于开始未被拒绝';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
    BEGIN
        UPDATE public.t_analysis_run SET status = 'UNKNOWN' WHERE analysis_run_id = 'run-a1';
        RAISE EXCEPTION '非法枚举未被拒绝';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END $$;

UPDATE public.t_analysis_run SET status = 'SUCCEEDED', finished_at = '2026-09-05 10:01:00'
WHERE analysis_run_id = 'run-a1';
INSERT INTO public.t_analysis_run (analysis_run_id, assessment_id, project_id, status, started_at)
VALUES ('run-a2', 1, 10, 'RUNNING', '2026-09-05 11:00:00');
UPDATE public.t_analysis_run SET status = 'FAILED', finished_at = '2026-09-05 11:01:00'
WHERE analysis_run_id = 'run-a2';

DO $$
BEGIN
    IF (SELECT count(*) FROM public.t_analysis_run WHERE assessment_id = 1) <> 2
        OR (SELECT status FROM public.t_analysis_run WHERE analysis_run_id = 'run-a1') <> 'SUCCEEDED'
        OR (SELECT status FROM public.t_analysis_run WHERE analysis_run_id = 'run-a2') <> 'FAILED'
        OR (SELECT status FROM public.t_analysis_run WHERE analysis_run_id = 'run-b1') <> 'RUNNING' THEN
        RAISE EXCEPTION '两次运行或两个评估的独立性检查失败';
    END IF;
END $$;
SELECT analysis_run_id, assessment_id, project_id, status, version FROM public.t_analysis_run
ORDER BY analysis_run_id;

