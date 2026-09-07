-- 在已执行 001～003 的业务库运行一次；历史文件没有可靠的原始名，保持 NULL。
BEGIN;

ALTER TABLE public.t_project_file
    ADD COLUMN original_file_name VARCHAR(512);

ALTER TABLE public.t_project_file
    ADD CONSTRAINT ck_project_file_original_name_not_blank
    CHECK (original_file_name IS NULL OR btrim(original_file_name) <> '');

COMMENT ON COLUMN public.t_project_file.original_file_name IS
    '上传时浏览器上报的原始文件名，仅用于展示和证据回溯；历史记录允许为空';

COMMIT;
