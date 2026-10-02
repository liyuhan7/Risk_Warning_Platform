package com.riskwarning.common.dto.analysis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 真 PostgreSQL 的独立 schema 测试；所有 DDL/数据位于事务内并最终回滚。 */
@EnabledIfSystemProperty(named = "analysis.pg.test", matches = "true")
class AnalysisContractPostgresTest {
    @Test
    void freshSchemaEnforcesResultAndAuditScope() throws Exception {
        String namespace = "analysis_contract_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(env("ANALYSIS_TEST_PG_URL", "jdbc:postgresql://localhost:5432/risk_warning_platform"),
                env("ANALYSIS_TEST_PG_USER", "postgres"), env("ANALYSIS_TEST_PG_PASSWORD", "password"))) {
            c.setAutoCommit(false);
            try {
                execute(c, "CREATE SCHEMA " + namespace);
                execute(c, "SET LOCAL search_path TO " + namespace);
                String ddl = read("documents/schema.sql").replace("public.", namespace + ".");
                execute(c, ddl);
                // 只构造测试事务的外键父记录，不读取或改写 public 业务数据。
                execute(c, "INSERT INTO t_enterprise(id,name,credit_code) VALUES (1,'contract','contract')");
                execute(c, "INSERT INTO t_project(id,enterprise_id,name) VALUES (1,1,'contract')");
                execute(c, "INSERT INTO t_assessment_result(id,project_id) VALUES (1,1)");
                execute(c, "INSERT INTO t_analysis_run(analysis_run_id,assessment_id,project_id,status,started_at) VALUES ('run',1,1,'RUNNING',now())");
                execute(c, result("old", "1.0", "NULL,NULL,NULL"));
                execute(c, result("range", "1.1", "NULL,0,'day'"));
                assertRejected(c, result("badunit", "1.1", "NULL,1,NULL"));
                assertRejected(c, result("mixed", "1.1", "false,1,'day'"));
                assertRejected(c, result("nan", "1.1", "NULL,'NaN'::float8,'day'"));
                assertRejected(c, result("infinity", "1.1", "NULL,'Infinity'::float8,'day'"));
                assertRejected(c, result("version", "2.0", "NULL,NULL,NULL"));
                execute(c, "DELETE FROM t_analysis_result WHERE id='range'");

                execute(c, result("binary", "1.1", "false,NULL,NULL"));
                assertRejected(c, result("badunit2", "1.1", "NULL,1,NULL"));
                String attempt = "INSERT INTO t_analysis_attempt(project_id,assessment_id,analysis_run_id,behavior_id,attempt_no,context_hash,model_version,prompt_version,status,created_at) VALUES (1,1,'run','b',1,'hash','m','p','PROVIDER_FAILED',now())";
                execute(c, attempt);
                assertRejected(c, attempt);
                assertRejected(c, attempt.replace("'b',1", "'other',0"));
                assertRejected(c, attempt.replace("VALUES (1,1", "VALUES (2,1").replace("'b',1", "'other',1"));
                assertRejected(c, attempt.replace("'b',1", "'other',1").replace("'PROVIDER_FAILED'", "'BAD'"));
                String audit = "INSERT INTO t_rule_evaluation_audit(project_id,assessment_id,analysis_run_id,indicator_id,input_hash,status,conflict,created_at,updated_at) VALUES (1,1,'run','i','hash','UNSUPPORTED_RULE',false,now(),now())";
                execute(c, audit);
                assertRejected(c, audit);
                assertRejected(c, audit.replace("VALUES (1,1", "VALUES (2,1").replace("'i'", "'other'"));
                execute(c, "INSERT INTO t_retrieval_audit(project_id,assessment_id,analysis_run_id,behavior_id,query_text,query_template_version,filter_version,filter_enabled,filter_applied,filter_expression,retrieval_status,analysis_status,created_at,updated_at) VALUES (1,1,'run','b','query','q-v1','f-v1',false,false,'{}','SUCCESS','WAITING_ANALYSIS',now(),now())");
                assertRejected(c, "UPDATE t_analysis_run SET execution_mode='LEGACY' WHERE analysis_run_id='run'");
                assertRejected(c, "UPDATE t_analysis_run SET behavior_input_hash='invalid' WHERE analysis_run_id='run'");
                execute(c, "UPDATE t_analysis_run SET expected_behavior_ids='[\"b\"]',behavior_input_hash='" + new String(new char[64]).replace('\0', 'a') + "' WHERE analysis_run_id='run'");
                assertRejected(c, "UPDATE t_analysis_run SET analysis_summary='[]' WHERE analysis_run_id='run'");
                execute(c, "UPDATE t_analysis_run SET analysis_summary='{\"completionKind\":\"FULL\"}' WHERE analysis_run_id='run'");

            } finally {
                c.rollback();
            }
            try (PreparedStatement st = c.prepareStatement("SELECT count(*) FROM pg_namespace WHERE nspname=?")) {
                st.setString(1, namespace);
                try (ResultSet rs = st.executeQuery()) { rs.next(); assertEquals(0, rs.getInt(1)); }
            }
        }
    }

    private String result(String id, String version, String bridge) {
        return "INSERT INTO t_analysis_result(id,schema_version,analysis_run_id,assessment_id,behavior_id,evidence_ids,indicator_id,applicable,enterprise_fact,compliance_status,confidence,reasoning,model_version,prompt_version,analyzed_at,condition_met,observed_value,observed_unit) VALUES ('"
                + id + "','" + version + "','run',1,'" + id + "','[\"e\"]','i','APPLICABLE','fact','COMPLIANT',0.8,'reason','m','p',now()," + bridge + ")";
    }

    private void assertRejected(Connection c, String sql) throws Exception {
        Savepoint sp = c.setSavepoint();
        SQLException error = assertThrows(SQLException.class, () -> execute(c, sql));
        assertTrue(error.getSQLState().startsWith("23"), error.toString());
        c.rollback(sp);
        c.releaseSavepoint(sp);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) { st.execute(sql); }
    }
    private static String read(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.exists(root.resolve("documents/schema.sql"))) root = root.getParent();
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
