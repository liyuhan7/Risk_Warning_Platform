package com.riskwarning.common.reliability;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/** 可靠模式启动前校验数据库结构，避免任务在缺表或旧约束下静默停摆。 */
public class ReliabilitySchemaGuard {

    private final JdbcTemplate jdbc;

    public ReliabilitySchemaGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void verify() {
        List<String> missing = new ArrayList<>();
        checkCount(missing, "t_durable_work 核心列", 13,
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 't_durable_work' "
                        + "AND column_name IN ('id','namespace','kind','task_key','payload','state',"
                        + "'attempts','available_at','lease_until','worker_id','last_error',"
                        + "'created_at','updated_at')");
        // 唯一约束通过索引定义核验实际唯一性与列集，避免同名弱化约束漏报。
        checkCount(missing, "t_durable_work 唯一约束 uq_durable_work_task", 1,
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename = 't_durable_work' "
                        + "AND indexname = 'uq_durable_work_task' "
                        + "AND indexdef LIKE 'CREATE UNIQUE%' "
                        + "AND indexdef LIKE '%(namespace, kind, task_key)%'");
        checkCount(missing, "t_durable_work 领取索引 ix_durable_work_claim", 1,
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename = 't_durable_work' "
                        + "AND indexname = 'ix_durable_work_claim' "
                        + "AND indexdef LIKE '%(namespace, kind, state, available_at, lease_until)%'");
        checkCount(missing, "t_assessment_result.source_task_id", 1,
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 't_assessment_result' "
                        + "AND column_name = 'source_task_id' AND data_type = 'character varying' "
                        + "AND character_maximum_length = 160");
        checkCount(missing, "t_assessment_result 唯一索引 uq_assessment_source_task", 1,
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename = 't_assessment_result' "
                        + "AND indexname = 'uq_assessment_source_task' "
                        + "AND indexdef LIKE 'CREATE UNIQUE%' "
                        + "AND indexdef LIKE '%(source_task_id)%'");
        checkCount(missing, "t_durable_work.lease_token", 1,
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 't_durable_work' "
                        + "AND column_name = 'lease_token' AND data_type = 'character varying' "
                        + "AND character_maximum_length = 64");
        // 租约约束必须同时限定三个所有权字段，防止被弱化为只含 lease_token。
        checkCount(missing, "t_durable_work 租约约束 ck_durable_work_lease", 1,
                "SELECT COUNT(*) FROM pg_constraint constraint_info "
                        + "JOIN pg_class table_info ON table_info.oid = constraint_info.conrelid "
                        + "JOIN pg_namespace schema_info ON schema_info.oid = table_info.relnamespace "
                        + "WHERE schema_info.nspname = 'public' AND table_info.relname = 't_durable_work' "
                        + "AND constraint_info.conname = 'ck_durable_work_lease' "
                        + "AND pg_get_constraintdef(constraint_info.oid) LIKE '%CHECK%' "
                        + "AND pg_get_constraintdef(constraint_info.oid) LIKE '%lease_token%' "
                        + "AND pg_get_constraintdef(constraint_info.oid) LIKE '%worker_id%' "
                        + "AND pg_get_constraintdef(constraint_info.oid) LIKE '%lease_until%'");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("可靠模式数据库结构不完整: " + String.join(", ", missing)
                    + "。请按 documents/schema.sql 初始化数据库，或联系维护者获取结构升级脚本");
        }
    }

    private void checkCount(List<String> missing, String item, int expected, String sql) {
        try {
            Integer count = jdbc.queryForObject(sql, Integer.class);
            if (count == null || count != expected) {
                missing.add(item);
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("可靠模式数据库结构校验失败（" + item + "）: "
                    + failure.getMessage(), failure);
        }
    }
}
