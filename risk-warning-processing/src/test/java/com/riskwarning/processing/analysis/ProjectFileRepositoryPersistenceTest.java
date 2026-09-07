package com.riskwarning.processing.analysis;

import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.processing.repository.ProjectFileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** 仅连接显式提供的专用测试库，须先运行 analysis_run_constraints.sql。 */
@EnabledIfEnvironmentVariable(named = "P1_TEST_DATABASE_URL", matches = ".+")
class ProjectFileRepositoryPersistenceTest {

    @Test
    void queriesSourceDocumentByAssessmentAndProjectScope() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(System.getenv("P1_TEST_DATABASE_URL"));
        dataSource.setUsername(System.getenv("P1_TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("P1_TEST_DATABASE_PASSWORD"));
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.riskwarning.common.po.file");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties properties = new Properties();
        properties.setProperty("hibernate.hbm2ddl.auto", "validate");
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        properties.setProperty("hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        properties.setProperty("hibernate.physical_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        factory.setJpaProperties(properties);
        factory.afterPropertiesSet();
        EntityManagerFactory emf = factory.getObject();
        assertNotNull(emf);
        EntityManager entityManager = emf.createEntityManager();
        try {
            ProjectFileRepository repository = new JpaRepositoryFactory(entityManager)
                    .getRepository(ProjectFileRepository.class);
            ProjectFile document = repository.findByIdAndProjectIdAndAssessmentId(101L, 10L, 1L)
                    .orElseThrow(() -> new AssertionError("找不到本次评估的源文件"));
            assertEquals("assessment-a.pdf", document.getFilePath());
            assertEquals("企业管理制度.pdf", document.getOriginalFileName());
            assertFalse(repository.findByIdAndProjectIdAndAssessmentId(101L, 10L, 2L).isPresent());
            assertFalse(repository.findByIdAndProjectIdAndAssessmentId(103L, 10L, 1L).isPresent());
        } finally {
            entityManager.close();
            factory.destroy();
        }
    }
}
