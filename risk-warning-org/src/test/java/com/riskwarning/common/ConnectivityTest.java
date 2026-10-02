package com.riskwarning.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.org.repository.AssessmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 生产评估 Repository 的真实 PostgreSQL 验证，只写随机 schema；需要运行中的 PostgreSQL。 */
@EnabledIfSystemProperty(named = "assessment.repository.integration", matches = "true")
class ConnectivityTest {
    @Test void assessmentPersistsAndCanBeReadAfterJpaRestart() throws Exception {
        String namespace = "assessment_repo_" + UUID.randomUUID().toString().replace("-", "");
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("documents/schema.sql"))) root = root.getParent();
        assertNotNull(root, "未找到仓库数据库定义");
        String definition = new String(Files.readAllBytes(root.resolve("documents/schema.sql")), StandardCharsets.UTF_8)
                .replace("public.", namespace + ".");
        DriverManagerDataSource admin = dataSource(env("ANALYSIS_TEST_PG_URL", "jdbc:postgresql://localhost:5432/risk_warning_platform"));
        JdbcTemplate adminSql = new JdbcTemplate(admin);
        DriverManagerDataSource scoped = dataSource(admin.getUrl() + (admin.getUrl().contains("?") ? "&" : "?") + "currentSchema=" + namespace);
        JdbcTemplate sql = new JdbcTemplate(scoped);
        LocalContainerEntityManagerFactoryBean factory = null;
        boolean created = false;
        try {
            adminSql.execute("CREATE SCHEMA " + namespace); created = true;
            assertEquals(namespace, sql.queryForObject("SELECT current_schema()", String.class));
            sql.execute(definition);
            sql.execute("INSERT INTO t_enterprise(id,name,credit_code) VALUES(1,'controlled repository','controlled repository')");
            sql.execute("INSERT INTO t_project(id,enterprise_id,name) VALUES(1,1,'controlled repository')");
            factory = factory(scoped, namespace);
            EntityManagerFactory emf = factory.getObject(); assertNotNull(emf);
            AssessmentRepository repository = repository(emf);
            TransactionTemplate transactions = new TransactionTemplate(new JpaTransactionManager(emf));
            LocalDateTime now = LocalDateTime.now().withNano(0);
            Assessment first = transactions.execute(status -> repository.saveAndFlush(assessment(now, "first", 0.0)));
            Assessment latest = transactions.execute(status -> repository.saveAndFlush(assessment(now, "latest", 0.4)));
            assertNotNull(first); assertNotNull(latest); assertNotEquals(first.getId(), latest.getId());
            assertEquals(2L, sql.queryForObject("SELECT count(*) FROM t_assessment_result", Long.class));
            assertEquals("{}", sql.queryForObject("SELECT details::text FROM t_assessment_result WHERE id=?", String.class, first.getId()));
            assertEquals("LOW_RISK", sql.queryForObject("SELECT overall_risk_level FROM t_assessment_result WHERE id=?", String.class, first.getId()));
            assertEquals(0.0, sql.queryForObject("SELECT overall_score FROM t_assessment_result WHERE id=?", Double.class, first.getId()));
            factory.destroy(); factory = null;
            factory = factory(scoped, namespace);
            AssessmentRepository restarted = repository(factory.getObject());
            Assessment reloaded = restarted.findById(first.getId().longValue());
            assertNotNull(reloaded); assertEquals(first.getId(), reloaded.getId());
            assertEquals(0.0, reloaded.getOverallScore()); assertEquals(RiskLevelEnum.LOW_RISK, reloaded.getOverallRiskLevel());
            assertEquals(AssessmentStatusEnum.ASSESSING, reloaded.getStatus()); assertEquals(now, reloaded.getAssessmentDate());
            assertEquals(latest.getId(), restarted.findFirstByProjectIdOrderByIdDesc(1L).get().getId());
            assertEquals(latest.getId(), restarted.findBySourceTaskId("latest").get().getId());
            assertNull(restarted.findById(Long.MAX_VALUE));
        } finally {
            if (factory != null) factory.destroy();
            if (created) {
                if (!namespace.matches("assessment_repo_[a-f0-9]{32}")) throw new IllegalStateException("测试清理目标不合法");
                // 保存准确 schema 的定义及全部数据后，才允许删除本次创建的资源。
                ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
                ObjectNode backup = mapper.createObjectNode();
                backup.put("database", sql.queryForObject("SELECT current_database()", String.class));
                backup.put("schema", namespace); backup.put("definition", definition);
                ObjectNode tables = backup.putObject("tables");
                for (String table : sql.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' ORDER BY table_name", String.class, namespace)) {
                    if (!table.matches("[a-z_]+")) throw new IllegalStateException("测试备份表名不合法");
                    com.fasterxml.jackson.databind.node.ArrayNode rows = tables.putArray(table);
                    for (String row : sql.queryForList("SELECT row_to_json(record)::text FROM " + namespace + "." + table + " record", String.class)) {
                        rows.add(mapper.readTree(row));
                    }
                }
                Path directory = root.resolve("tmp"); Files.createDirectories(directory);
                Path path = directory.resolve(namespace + "-backup.json");
                Files.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(backup), StandardOpenOption.CREATE_NEW);
                assertEquals(namespace, mapper.readTree(Files.readAllBytes(path)).path("schema").asText());
                adminSql.execute("DROP SCHEMA " + namespace + " CASCADE");
                assertEquals(0L, adminSql.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Long.class, namespace));
            }
        }
    }

    private Assessment assessment(LocalDateTime time, String taskId, double score) {
        return Assessment.builder().projectId(1L).sourceTaskId(taskId).assessmentDate(time).overallScore(score)
                .overallRiskLevel(RiskLevelEnum.LOW_RISK).details("{}").recommendations("")
                .status(AssessmentStatusEnum.ASSESSING).createdAt(time).build();
    }

    private LocalContainerEntityManagerFactoryBean factory(DriverManagerDataSource source, String namespace) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source); factory.setPackagesToScan("com.riskwarning.common.po");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties(); properties.setProperty("hibernate.hbm2ddl.auto", "none");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        properties.setProperty("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        properties.setProperty("hibernate.default_schema", namespace); factory.setJpaProperties(properties); factory.afterPropertiesSet();
        return factory;
    }

    private AssessmentRepository repository(EntityManagerFactory factory) {
        assertNotNull(factory);
        return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(AssessmentRepository.class);
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource(); source.setUrl(url);
        source.setUsername(env("ANALYSIS_TEST_PG_USER", "postgres")); source.setPassword(env("ANALYSIS_TEST_PG_PASSWORD", "password"));
        return source;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key); return value == null || value.isEmpty() ? fallback : value;
    }
}
