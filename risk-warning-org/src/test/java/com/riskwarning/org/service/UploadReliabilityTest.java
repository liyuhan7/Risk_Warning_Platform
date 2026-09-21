package com.riskwarning.org.service;

import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.reliability.DurableWorkProperties;
import com.riskwarning.common.reliability.DurableWorkStore;
import com.riskwarning.common.reliability.DurableWork;
import com.riskwarning.common.reliability.DurableWorker;
import com.riskwarning.common.reliability.DurableWorkContext;
import com.riskwarning.common.reliability.KafkaOutboxCodec;
import com.riskwarning.common.reliability.TransactionalKafkaOutbox;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.repository.AnalysisRunRepository;
import com.riskwarning.org.repository.AssessmentRepository;
import com.riskwarning.org.repository.FileRepository;
import com.riskwarning.org.task.ReliabilityPostgresEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-03 Step D：真实 PostgreSQL 验证上传确认链。
 * DB 失败时业务事务完全回滚；文件重试覆盖稳定路径不追加；
 * 事务提交后重试由 sourceTaskId 幂等命中；cleanup 失败可继续重试。
 */
@EnabledIfEnvironmentVariable(named = "R1_03_IT_ENABLED", matches = "true")
class UploadReliabilityTest {

    private static ReliabilityPostgresEnv pgEnv;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static final AtomicLong PROJECT_SEQUENCE = new AtomicLong(910_555_000L);

    private long projectId;
    private String taskId;
    private UploadConfirmProcessor processor;

    @BeforeAll
    static void startEnvironment() throws Exception {
        pgEnv = ReliabilityPostgresEnv.start();
        jdbc = pgEnv.getJdbcTemplate();
        context = buildContext(pgEnv);
    }

    @AfterAll
    static void stopEnvironment() {
        context.close();
    }

    @BeforeEach
    void setup() {
        projectId = PROJECT_SEQUENCE.incrementAndGet();
        taskId = "upload:" + projectId + ":r103-" + UUID();
        processor = context.getBean(UploadConfirmProcessor.class);
    }

    @AfterEach
    void cleanup() {
        deleteBusinessDataByProject(projectId);
        deleteOutboxByTaskPrefix(taskId + ":%");
        jdbc.update("DELETE FROM t_durable_work WHERE task_key = ?", taskId);
        FileUtils.delDirectory(Constants.getPersistFileDirPath(projectId));
        FileUtils.delDirectory(Constants.getTempFileDirPath(projectId, "u-1"));
        FileUtils.delDirectory(Constants.getTempFileDirPath(projectId, "u-2"));
    }

    @Test
    void dbFailureDuringFileProcessingRollsBackEverything() throws Exception {
        writeChunks("u-1", "AAA");
        writeChunks("u-2", "BBB");
        // 第二个文件分片目录缺失：合并抛异常，事务整体回滚
        deleteRecursively(Paths.get(Constants.getTempFileDirPath(projectId, "u-2")));

        assertThrows(RuntimeException.class, () -> processor.process(task("u-1", "u-2")));

        assertEquals(0L, count("SELECT COUNT(*) FROM t_assessment_result WHERE project_id = ?", projectId));
        assertEquals(0L, count("SELECT COUNT(*) FROM t_analysis_run WHERE project_id = ?", projectId));
        assertEquals(0L, count("SELECT COUNT(*) FROM t_project_file WHERE project_id = ?", projectId));
        assertEquals(0L, count("SELECT COUNT(*) FROM t_durable_work WHERE task_key LIKE ?", taskId + ":%"));
        // 文件系统不被数据库事务回滚：u-1 的稳定目标文件已写出，等待重试覆盖
        assertTrue(Files.exists(targetFile("u-1")));
    }

    @Test
    void retryAfterMergeCrashOverwritesStableFileWithoutDuplication() throws Exception {
        writeChunks("u-1", "AAA");
        writeChunks("u-2", "BBB");
        deleteRecursively(Paths.get(Constants.getTempFileDirPath(projectId, "u-2")));

        assertThrows(RuntimeException.class, () -> processor.process(task("u-1", "u-2")));

        // 恢复分片目录后重试：稳定目标路径被覆盖，不追加、不产生重复文件
        writeChunks("u-2", "BBB");
        UploadConfirmProcessor.UploadConfirmOutcome outcome = processor.process(task("u-1", "u-2"));
        assertTrue(outcome.getAssessmentId() > 0);

        assertEquals("AAA", fileContent(targetFile("u-1")), "重试必须覆盖同一稳定文件，不得追加");
        assertEquals("BBB", fileContent(targetFile("u-2")));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_assessment_result WHERE source_task_id = ?", taskId));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_analysis_run WHERE assessment_id = ?", outcome.getAssessmentId()));
        assertEquals(2L, count("SELECT COUNT(*) FROM t_project_file WHERE assessment_id = ?", outcome.getAssessmentId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_durable_work WHERE task_key = ?", taskId + ":behavior"));
    }

    @Test
    void retryAfterCommittedTransactionHitsSourceTaskIdWithoutDuplication() throws Exception {
        writeChunks("u-1", "AAA");
        UploadConfirmDto task = task("u-1");

        UploadConfirmProcessor.UploadConfirmOutcome first = processor.process(task);
        assertTrue(first.getAssessmentId() > 0);
        assertEquals(1L, count("SELECT COUNT(*) FROM t_assessment_result WHERE source_task_id = ?", taskId));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_durable_work WHERE task_key = ?", taskId + ":behavior"));

        // 业务事务已提交、Durable Work 未标 DONE 时崩溃：重试幂等命中同一评估
        UploadConfirmProcessor.UploadConfirmOutcome retry = processor.process(task);
        assertTrue(retry.isAlreadyCompleted());
        assertEquals(first.getAssessmentId(), retry.getAssessmentId());
        assertEquals(1L, count("SELECT COUNT(*) FROM t_assessment_result WHERE source_task_id = ?", taskId));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_project_file WHERE assessment_id = ?", first.getAssessmentId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_analysis_run WHERE assessment_id = ?", first.getAssessmentId()));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_durable_work WHERE task_key = ?", taskId + ":behavior"));
    }

    @Test
    void cleanupFailureRetriesWithoutCreatingDuplicateAssessment() throws Exception {
        writeChunks("u-1", "AAA");
        UploadConfirmDto task = task("u-1");

        UploadConfirmProcessor.UploadConfirmOutcome outcome = processor.process(task);
        assertTrue(outcome.getAssessmentId() > 0);

        // cleanup 首次失败：任务回到 READY；重试命中幂等检查后只补做 cleanup
        AtomicInteger cleanupFailures = new AtomicInteger(1);
        com.riskwarning.org.service.UploadConfirmCleanup failingCleanup =
                new com.riskwarning.org.service.UploadConfirmCleanup(null) {
                    @Override
                    public void cleanup(UploadConfirmDto uploadTask) {
                        if (cleanupFailures.getAndDecrement() > 0) {
                            throw new IllegalStateException("cleanup failed once");
                        }
                    }
                };
        com.riskwarning.org.task.UploadConfirmWorkHandler handler =
                new com.riskwarning.org.task.UploadConfirmWorkHandler(
                        new com.riskwarning.org.task.UploadConfirmTaskCodec(), processor, failingCleanup);
        DurableWorkStore store = store();
        store.enqueue("UPLOAD_CONFIRM", taskId, payloadOf(task));
        DurableWork work = store.claim("UPLOAD_CONFIRM", "worker-1");
        assertNotNull0(work);

        DurableWorker worker = new DurableWorker(store,
                new ArrayList<com.riskwarning.common.reliability.DurableWorkHandler>(),
                properties(), new AssessmentFlowLogger());
        // DurableWorker.execute 为包私有，测试通过反射驱动真实执行路径
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "execute", handler, work);
        assertEquals("READY", workState(taskId), "cleanup 失败必须回 READY 供重试");

        DurableWork retry = claimWithin(store, 5);
        assertTrue(retry.getAttempts() >= 2);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(worker, "execute", handler, retry);
        assertEquals("DONE", workState(taskId));
        assertEquals(1L, count("SELECT COUNT(*) FROM t_assessment_result WHERE source_task_id = ?", taskId));
    }

    // ---------- 支撑 ----------

    private static String UUID() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static void assertNotNull0(Object value) {
        if (value == null) {
            throw new AssertionError("claim 返回空任务");
        }
    }

    private DurableWorkStore store() {
        return new DurableWorkStore(jdbc, transactionTemplate(), properties());
    }

    private TransactionTemplate transactionTemplate() {
        return pgEnv.transactionTemplate();
    }

    private DurableWorkProperties properties() {
        DurableWorkProperties properties = new DurableWorkProperties();
        properties.setEnabled(true);
        properties.setNamespace("r103-upload-it");
        properties.setLeaseDuration(Duration.ofSeconds(15));
        properties.setRetryDelay(Duration.ofMillis(200));
        return properties;
    }

    private DurableWork claimWithin(DurableWorkStore store, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            DurableWork work = store.claim("UPLOAD_CONFIRM", "worker-retry");
            if (work != null) {
                return work;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("重试任务未回到 READY");
    }

    private String payloadOf(UploadConfirmDto task) {
        return new com.riskwarning.org.task.UploadConfirmTaskCodec().encode(task);
    }

    private String workState(String taskKey) {
        return jdbc.queryForObject(
                "SELECT state FROM t_durable_work WHERE task_key = ?", String.class, taskKey);
    }

    private long count(String sql, Object parameter) {
        Long value = jdbc.queryForObject(sql, Long.class, parameter);
        return value == null ? 0L : value;
    }

    private void deleteBusinessDataByProject(long projectId) {
        jdbc.update("DELETE FROM t_analysis_run WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM t_project_file WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM t_assessment_result WHERE project_id = ?", projectId);
    }

    private void deleteOutboxByTaskPrefix(String taskKeyPrefix) {
        jdbc.update("DELETE FROM t_durable_work WHERE task_key LIKE ?", taskKeyPrefix);
    }

    private Path targetFile(String uploadId) {
        return Paths.get(Constants.getPersistFileDirPath(projectId)
                + com.riskwarning.common.utils.StringUtils.generateFileName(projectId, uploadId)
                + ".pdf");
    }

    private String fileContent(Path file) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(file), "UTF-8");
    }

    private void writeChunks(String uploadId, String content) throws Exception {
        Path dir = Paths.get(Constants.getTempFileDirPath(projectId, uploadId));
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve("0"), content.getBytes("UTF-8"));
    }

    private void deleteRecursively(Path directory) {
        java.io.File file = directory.toFile();
        if (file.exists() && file.isDirectory()) {
            for (java.io.File child : file.listFiles()) {
                if (child.isDirectory()) {
                    deleteRecursively(child.toPath());
                } else {
                    child.delete();
                }
            }
            file.delete();
        }
    }

    private UploadConfirmDto task(String... uploadIds) {
        List<com.riskwarning.org.entity.dto.UploadFileDto> files = new ArrayList<>();
        for (String uploadId : uploadIds) {
            files.add(com.riskwarning.org.entity.dto.UploadFileDto.builder()
                    .projectId(projectId).uploadId(uploadId).userId(8L)
                    .filePath(Constants.getTempFileDirPath(projectId, uploadId))
                    .totalChunks(1).fileSuffix("pdf").build());
        }
        return com.riskwarning.org.entity.dto.UploadConfirmDto.builder()
                .taskId(taskId).projectId(projectId).userId(8L)
                .files(files).build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T repository(Class<T> repositoryInterface, EntityManagerFactory entityManagerFactory) {
        JpaRepositoryFactory factory = new JpaRepositoryFactory(
                SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
        return factory.getRepository(repositoryInterface);
    }

    private static AnnotationConfigApplicationContext buildContext(ReliabilityPostgresEnv env) {
        DataSource dataSource = env.getDataSource();

        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPackagesToScan("com.riskwarning.common.po");
        factoryBean.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        // 与 Spring Boot 生产配置保持一致：驼峰属性映射为下划线列名
        java.util.Map<String, String> jpaProperties = new java.util.HashMap<>();
        jpaProperties.put("hibernate.hbm2ddl.auto", "none");
        jpaProperties.put("hibernate.physical_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        jpaProperties.put("hibernate.implicit_naming_strategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        factoryBean.setJpaPropertyMap(jpaProperties);
        factoryBean.afterPropertiesSet();
        EntityManagerFactory entityManagerFactory = factoryBean.getObject();

        JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(javax.sql.DataSource.class, () -> dataSource);
        context.registerBean(EntityManagerFactory.class, () -> entityManagerFactory);
        context.registerBean(PlatformTransactionManager.class, () -> transactionManager);
        context.registerBean(TransactionTemplate.class, () -> new TransactionTemplate(transactionManager));

        context.registerBean(AssessmentRepository.class,
                () -> repository(AssessmentRepository.class, entityManagerFactory));
        context.registerBean(FileRepository.class,
                () -> repository(FileRepository.class, entityManagerFactory));
        context.registerBean(AnalysisRunRepository.class,
                () -> repository(AnalysisRunRepository.class, entityManagerFactory));

        DurableWorkProperties properties = new DurableWorkProperties();
        properties.setEnabled(true);
        properties.setNamespace("r103-upload-it");
        properties.setLeaseDuration(Duration.ofSeconds(15));
        properties.setRetryDelay(Duration.ofMillis(200));
        DurableWorkStore store = new DurableWorkStore(
                new JdbcTemplate(dataSource), new TransactionTemplate(transactionManager), properties);
        context.registerBean(DurableWorkStore.class, () -> store);
        context.registerBean(com.riskwarning.common.reliability.KafkaOutbox.class,
                () -> new TransactionalKafkaOutbox(store, new KafkaOutboxCodec(),
                        new AssessmentFlowLogger()));
        context.registerBean(AnalysisRunService.class,
                () -> new AnalysisRunService(context.getBean(AnalysisRunRepository.class),
                        context.getBean(AssessmentRepository.class)));
        context.registerBean(AnalysisRunMessageDispatcher.class,
                () -> new AnalysisRunMessageDispatcher(null,
                        context.getBean(AnalysisRunService.class),
                        context.getBean(com.riskwarning.common.reliability.KafkaOutbox.class)));
        context.registerBean(UploadConfirmProcessor.class,
                () -> new UploadConfirmProcessor(context.getBean(AssessmentRepository.class),
                        context.getBean(FileRepository.class),
                        context.getBean(AnalysisRunService.class),
                        context.getBean(AnalysisRunMessageDispatcher.class),
                        context.getBean(TransactionTemplate.class),
                        null, new AssessmentFlowLogger()));
        context.refresh();
        return context;
    }
}
