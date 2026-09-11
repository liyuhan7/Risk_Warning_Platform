package com.riskwarning.processing.analysis;

import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.processing.repository.EvidenceChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 仅连接显式提供的专用测试库，须先运行 analysis_run_constraints.sql。 */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DATABASE_URL", matches = ".+")
class EvidenceChunkRepositoryPersistenceTest {

    @Test
    void queriesEvidenceInSourceDocumentOrder() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("P1_TEST_DATABASE_URL"));
        dataSource.setUsername(System.getenv("P1_TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("P1_TEST_DATABASE_PASSWORD"));
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.riskwarning.common.po.evidence");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        EntityManager entityManager = emf.createEntityManager();
        try {
            EvidenceChunkRepository repository = new JpaRepositoryFactory(entityManager)
                    .getRepository(EvidenceChunkRepository.class);
            EvidenceChunk expected = EvidenceChunk.create(101L, 10L, 1L, "assessment-a.pdf", 2, 0,
                    null, null, "repository evidence", LocalDateTime.of(2026, 9, 5, 10, 0));
            EvidenceChunk retry = EvidenceChunk.create(101L, 10L, 1L, "assessment-a.pdf", 2, 0,
                    null, null, "repository evidence", LocalDateTime.of(2026, 9, 5, 10, 1));
            entityManager.getTransaction().begin();
            repository.saveAndFlush(expected);
            repository.saveAndFlush(retry);
            entityManager.getTransaction().commit();
            entityManager.clear();

            EvidenceChunk chunk = repository.findBySourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(101L)
                    .stream().filter(item -> expected.getId().equals(item.getId())).findFirst()
                    .orElseThrow(() -> new AssertionError("未查询到持久化证据"));
            assertEquals(expected.getId(), chunk.getId());
            assertEquals(Long.valueOf(10L), chunk.getProjectId());
            assertEquals(Integer.valueOf(2), chunk.getPageNumber());
            assertEquals(1, repository.findBySourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(101L)
                    .stream().filter(item -> Integer.valueOf(2).equals(item.getPageNumber())
                            && Integer.valueOf(0).equals(item.getSegmentIndex())).count());
        } finally {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            entityManager.close();
            factory.destroy();
        }
    }

    /** 验证 P1-08 新增派生查询的真实 SQL 解析；方法名拼错会在仓库创建时暴露。 */
    @Test
    void queriesEvidenceByAssessmentScopeAndIdBatch() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("P1_TEST_DATABASE_URL"));
        dataSource.setUsername(System.getenv("P1_TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("P1_TEST_DATABASE_PASSWORD"));
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.riskwarning.common.po.evidence");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        EntityManager entityManager = emf.createEntityManager();
        try {
            EvidenceChunkRepository repository = new JpaRepositoryFactory(entityManager)
                    .getRepository(EvidenceChunkRepository.class);
            EvidenceChunk scoped = EvidenceChunk.create(101L, 10L, 1L, "a.pdf", 1, 0,
                    null, null, "scoped text", LocalDateTime.of(2026, 9, 5, 10, 0));
            EvidenceChunk otherAssessment = EvidenceChunk.create(102L, 10L, 2L, "b.pdf", 1, 0,
                    null, null, "other assessment", LocalDateTime.of(2026, 9, 5, 10, 0));
            entityManager.getTransaction().begin();
            repository.saveAndFlush(scoped);
            repository.saveAndFlush(otherAssessment);
            entityManager.getTransaction().commit();
            entityManager.clear();

            // fixture 已预置一条同评估证据，且重跑时同主键覆盖，故断言用"包含/排除"而非精确计数
            java.util.List<EvidenceChunk> scopedList = repository
                    .findByProjectIdAndAssessmentIdOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                            10L, 1L);
            assertTrue(scopedList.stream().anyMatch(item -> scoped.getId().equals(item.getId())),
                    "评估作用域查询应包含本评估证据");
            assertTrue(scopedList.stream().noneMatch(item -> otherAssessment.getId().equals(item.getId())),
                    "评估作用域查询不应混入其他评估证据");
            assertEquals(1, repository
                    .findByProjectIdAndAssessmentIdAndSourceDocumentIdOrderByPageNumberAscSegmentIndexAsc(
                            10L, 1L, 101L).stream()
                            .filter(item -> scoped.getId().equals(item.getId())).count());
            assertEquals(1, repository
                    .findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(
                            java.util.Collections.singletonList(scoped.getId())).size());
        } finally {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
            entityManager.close();
            factory.destroy();
        }
    }
}
