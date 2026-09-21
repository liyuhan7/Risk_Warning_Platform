package com.riskwarning.processing.reliability;

import org.postgresql.Driver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * R1-03 真实 PostgreSQL 隔离环境（processing 模块副本）。
 * 与 common 模块共用同一隔离库与 DDL 文件；测试类不能跨模块共享，
 * 只保留本模块用到的连接与清理能力。
 */
public final class ReliabilityPostgresEnv {

    private static volatile boolean schemaReady;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    private ReliabilityPostgresEnv(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 建库并执行幂等 DDL；同一 JVM 只执行一次。 */
    public static ReliabilityPostgresEnv start() throws Exception {
        String host = envOr("R1_03_PG_HOST", "127.0.0.1");
        String port = envOr("R1_03_PG_PORT", "5432");
        String user = envOr("R1_03_PG_USER", "postgres");
        String password = envOr("R1_03_PG_PASSWORD", "password");
        String database = envOr("R1_03_PG_DB", "risk_warning_reliability_test");

        createDatabaseIfMissing(host, port, user, password, database);
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(
                new Driver(), "jdbc:postgresql://" + host + ":" + port + "/" + database, user, password);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        applySchemaIfMissing(jdbcTemplate);
        return new ReliabilityPostgresEnv(dataSource, jdbcTemplate);
    }

    public JdbcTemplate getJdbcTemplate() { return jdbcTemplate; }

    public DataSource getDataSource() { return dataSource; }

    public org.springframework.transaction.support.TransactionTemplate transactionTemplate() {
        PlatformTransactionManager manager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        return new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    /** 删除本测试 namespace 下全部 Durable Work。 */
    public void deleteDurableWork(String namespace) {
        jdbcTemplate.update("DELETE FROM t_durable_work WHERE namespace = ?", namespace);
    }

    /** 删除指定评估名下的运行、文件与评估记录。 */
    public void deleteBusinessData(Long assessmentId) {
        jdbcTemplate.update("DELETE FROM t_analysis_run WHERE assessment_id = ?", assessmentId);
        jdbcTemplate.update("DELETE FROM t_project_file WHERE assessment_id = ?", assessmentId);
        jdbcTemplate.update("DELETE FROM t_indicator_result WHERE assessment_id = ?", assessmentId);
        jdbcTemplate.update("DELETE FROM t_assessment_result WHERE id = ?", assessmentId);
    }

    private static void createDatabaseIfMissing(String host, String port, String user,
                                                 String password, String database) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/postgres", props);
             Statement statement = connection.createStatement();
             java.sql.ResultSet exists = statement.executeQuery(
                     "SELECT 1 FROM pg_database WHERE datname = '" + database + "'")) {
            if (!exists.next()) {
                statement.executeUpdate("CREATE DATABASE " + database);
            }
        }
    }

    private static void applySchema(JdbcTemplate jdbcTemplate, String sql) {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception exception) {
            throw new IllegalStateException("R1-03 测试 schema 执行失败", exception);
        }
    }

    private static Path locateSchemaFile() {
        List<Path> candidates = Arrays.asList(
                Paths.get("..", "test", "r1-03", "reliability-test-schema.sql"),
                Paths.get("test", "r1-03", "reliability-test-schema.sql"));
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) { return candidate; }
        }
        throw new IllegalStateException("找不到 reliability-test-schema.sql，请从模块目录运行测试");
    }

    private static synchronized void applySchemaIfMissing(JdbcTemplate jdbcTemplate) {
        if (schemaReady) { return; }
        Path file = locateSchemaFile();
        try {
            applySchema(jdbcTemplate, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法读取 R1-03 测试 schema: " + file, exception);
        }
        schemaReady = true;
    }

    private static String envOr(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}

