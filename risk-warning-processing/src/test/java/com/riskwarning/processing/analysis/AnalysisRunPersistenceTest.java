package com.riskwarning.processing.analysis;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.OptimisticLockException;
import java.time.LocalDateTime;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 仅连接显式提供的专用测试库，须先运行 analysis_run_constraints.sql。 */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DATABASE_URL", matches = ".+")
class AnalysisRunPersistenceTest {

    @Test
    void persistsScopeQueriesAndRejectsStaleTerminalUpdate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("P1_TEST_DATABASE_URL"));
        dataSource.setUsername(System.getenv("P1_TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("P1_TEST_DATABASE_PASSWORD"));
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.riskwarning.common.po.analysis");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        assertNotNull(emf);
        EntityManager first = emf.createEntityManager();
        EntityManager second = emf.createEntityManager();
        try {
            LocalDateTime now = LocalDateTime.of(2026, 9, 5, 12, 0);
            first.getTransaction().begin();
            first.createNativeQuery("DELETE FROM t_analysis_run WHERE assessment_id = 4")
                    .executeUpdate();
            first.createNativeQuery("INSERT INTO t_assessment_result (id, project_id) VALUES (4, 10) "
                    + "ON CONFLICT (id) DO NOTHING")
                    .executeUpdate();
            AnalysisRunRepository repository = new JpaRepositoryFactory(first)
                    .getRepository(AnalysisRunRepository.class);
            repository.saveAndFlush(AnalysisRun.start(new AnalysisScope(10L, 4L, "jpa-run"), now));
            first.getTransaction().commit();
            first.clear();

            AnalysisRun stored = repository.findByAnalysisRunIdAndAssessmentIdAndProjectId("jpa-run", 4L, 10L)
                    .orElseThrow(() -> new AssertionError("运行未持久化"));
            assertEquals(now, stored.getStartedAt());
            assertEquals(Long.valueOf(0), stored.getVersion());
            assertTrue(repository.existsByAssessmentIdAndStatus(4L, AnalysisRunStatus.RUNNING));
            assertFalse(repository.findByAnalysisRunIdAndAssessmentIdAndProjectId("jpa-run", 2L, 10L).isPresent());
            assertFalse(repository.findByAnalysisRunIdAndAssessmentIdAndProjectId("jpa-run", 4L, 20L).isPresent());
            AnalysisRun stale = second.find(AnalysisRun.class, "jpa-run");

            first.getTransaction().begin();
            stored.succeed(now.plusMinutes(1));
            first.getTransaction().commit();
            assertEquals(Long.valueOf(1), stored.getVersion());

            second.getTransaction().begin();
            stale.fail(now.plusMinutes(2));
            assertThrows(OptimisticLockException.class, second::flush);
            second.getTransaction().rollback();
            first.clear();
            AnalysisRun actual = first.find(AnalysisRun.class, "jpa-run");
            assertEquals(AnalysisRunStatus.SUCCEEDED, actual.getStatus());
            assertEquals(now.plusMinutes(1), actual.getFinishedAt());
        } finally {
            if (first.getTransaction().isActive()) {
                first.getTransaction().rollback();
            }
            if (second.getTransaction().isActive()) {
                second.getTransaction().rollback();
            }
            first.close();
            second.close();
            factory.destroy();
        }
    }

    /** 验证 P1-08 新增的"最近成功运行"解析在真实 SQL 下的排序与状态过滤。 */
    @Test
    void resolvesMostRecentSuccessfulRunIgnoringFailedOnes() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("P1_TEST_DATABASE_URL"));
        dataSource.setUsername(System.getenv("P1_TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("P1_TEST_DATABASE_PASSWORD"));
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.riskwarning.common.po.analysis");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        EntityManager entityManager = emf.createEntityManager();
        try {
            LocalDateTime now = LocalDateTime.of(2026, 9, 5, 12, 0);
            entityManager.getTransaction().begin();
            entityManager.createNativeQuery("DELETE FROM t_analysis_run WHERE assessment_id = 4")
                    .executeUpdate();
            entityManager.createNativeQuery("INSERT INTO t_assessment_result (id, project_id) VALUES (4, 10) "
                    + "ON CONFLICT (id) DO NOTHING")
                    .executeUpdate();
            AnalysisRunRepository repository = new JpaRepositoryFactory(entityManager)
                    .getRepository(AnalysisRunRepository.class);
            AnalysisRun older = AnalysisRun.start(new AnalysisScope(10L, 4L, "success-older"), now);
            older.succeed(now.plusMinutes(1));
            repository.saveAndFlush(older);
            AnalysisRun failed = AnalysisRun.start(new AnalysisScope(10L, 4L, "failed-run"), now.plusMinutes(2));
            failed.fail(now.plusMinutes(3));
            repository.saveAndFlush(failed);
            AnalysisRun newer = AnalysisRun.start(new AnalysisScope(10L, 4L, "success-newer"), now.plusMinutes(4));
            newer.succeed(now.plusMinutes(5));
            repository.saveAndFlush(newer);
            entityManager.getTransaction().commit();
            entityManager.clear();

            AnalysisRun resolved = repository
                    .findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(4L, AnalysisRunStatus.SUCCEEDED)
                    .orElseThrow(() -> new AssertionError("未解析到最近成功运行"));
            assertEquals("success-newer", resolved.getAnalysisRunId());
        } finally {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            entityManager.close();
            factory.destroy();
        }
    }
}
