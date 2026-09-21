package com.riskwarning.org.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AssessmentStatusEnum;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.constants.Constants;
import com.riskwarning.common.utils.FileUtils;
import com.riskwarning.common.utils.RedisUtil;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.org.entity.dto.UploadConfirmDto;
import com.riskwarning.org.entity.dto.UploadFileDto;
import com.riskwarning.org.repository.AssessmentRepository;
import com.riskwarning.org.repository.FileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadConfirmProcessorTest {

    private static final Long PROJECT_ID = 991001L;

    private final AssessmentRepository assessments = mock(AssessmentRepository.class);
    private final FileRepository files = mock(FileRepository.class);
    private final AnalysisRunService runs = mock(AnalysisRunService.class);
    private final AnalysisRunMessageDispatcher dispatcher = mock(AnalysisRunMessageDispatcher.class);
    private final RedisUtil redisUtil = mock(RedisUtil.class);
    private final AssessmentFlowLogger flowLogger = mock(AssessmentFlowLogger.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private UploadConfirmProcessor processor;

    @BeforeEach
    void setup() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        processor = new UploadConfirmProcessor(assessments, files, runs, dispatcher,
                new TransactionTemplate(transactionManager), redisUtil, flowLogger);
    }

    @AfterEach
    void tearDown() {
        FileUtils.delDirectory(Constants.getPersistFileDirPath(PROJECT_ID));
        FileUtils.delDirectory(Constants.getTempFileDirPath(PROJECT_ID, "upload-1"));
        FileUtils.delDirectory(Constants.getTempFileDirPath(PROJECT_ID, "upload-2"));
    }

    @Test
    void createsAssessmentWithSourceTaskIdAndDispatchesDeterministicMessage() throws Exception {
        Path chunkDir = chunkDirectory("upload-1", "content-1");
        UploadFileDto upload = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("upload-1").userId(8L)
                .filePath(chunkDir.toString()).fileHash("hash-1")
                .totalChunks(1).fileSuffix("pdf").originalFileName("制度.pdf").build();
        UploadConfirmDto task = UploadConfirmDto.builder()
                .taskId("upload:" + PROJECT_ID + ":abc").projectId(PROJECT_ID).userId(8L)
                .files(Collections.singletonList(upload)).build();
        when(assessments.findBySourceTaskId(task.getTaskId())).thenReturn(Optional.empty());
        when(assessments.saveAndFlush(any())).thenAnswer(invocation -> {
            Assessment assessment = invocation.getArgument(0);
            assessment.setId(9L);
            return assessment;
        });
        when(files.save(any(ProjectFile.class))).thenAnswer(invocation -> {
            ProjectFile projectFile = invocation.getArgument(0);
            projectFile.setId(101L);
            return projectFile;
        });

        UploadConfirmProcessor.UploadConfirmOutcome outcome = processor.process(task);

        assertFalse(outcome.isAlreadyCompleted());
        assertEquals(Long.valueOf(9L), outcome.getAssessmentId());

        ArgumentCaptor<Assessment> assessmentCaptor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessments).saveAndFlush(assessmentCaptor.capture());
        assertEquals(task.getTaskId(), assessmentCaptor.getValue().getSourceTaskId());
        assertEquals(AssessmentStatusEnum.TO_BE_ASSESSED, assessmentCaptor.getValue().getStatus());
        assertEquals(PROJECT_ID, assessmentCaptor.getValue().getProjectId());

        ArgumentCaptor<BehaviorProcessingTaskMessage> messageCaptor =
                ArgumentCaptor.forClass(BehaviorProcessingTaskMessage.class);
        ArgumentCaptor<AnalysisScope> scopeCaptor = ArgumentCaptor.forClass(AnalysisScope.class);
        verify(dispatcher).dispatch(scopeCaptor.capture(), messageCaptor.capture());
        // 稳定任务身份确定性派生首条消息，重复执行不会产生新的逻辑消息
        assertEquals(StringUtils.deriveMessageId(task.getTaskId(), "behavior"),
                messageCaptor.getValue().getMessageId());
        assertEquals(Long.valueOf(9L), scopeCaptor.getValue().getAssessmentId());

        ArgumentCaptor<AssessmentFlowEvent> flowCaptor = ArgumentCaptor.forClass(AssessmentFlowEvent.class);
        verify(flowLogger).info(flowCaptor.capture());
        assertEquals(AssessmentFlowStage.ANALYSIS_RUN_START, flowCaptor.getValue().getStage());
        assertEquals(AssessmentFlowStatus.SUCCEEDED, flowCaptor.getValue().getStatus());
        assertEquals(scopeCaptor.getValue().getAnalysisRunId(),
                flowCaptor.getValue().getAnalysisRunId());

        String targetFilePath = Constants.getPersistFileDirPath(PROJECT_ID)
                + StringUtils.generateFileName(PROJECT_ID, "upload-1") + ".pdf";
        assertEquals("content-1", new String(Files.readAllBytes(Paths.get(targetFilePath))));
    }

    @Test
    void skipsBusinessWhenSourceTaskAlreadyCommitted() {
        UploadConfirmDto task = completeTask();
        when(assessments.findBySourceTaskId(task.getTaskId()))
                .thenReturn(Optional.of(Assessment.builder().id(9L).projectId(PROJECT_ID).build()));

        UploadConfirmProcessor.UploadConfirmOutcome outcome = processor.process(task);

        assertTrue(outcome.isAlreadyCompleted());
        assertEquals(Long.valueOf(9L), outcome.getAssessmentId());
        verify(assessments, never()).saveAndFlush(any());
        verify(files, never()).save(any(ProjectFile.class));
        verify(dispatcher, never()).dispatch(any(), any());

        ArgumentCaptor<AssessmentFlowEvent> duplicateCaptor =
                ArgumentCaptor.forClass(AssessmentFlowEvent.class);
        verify(flowLogger).info(duplicateCaptor.capture());
        assertEquals(AssessmentFlowStage.ANALYSIS_RUN_START, duplicateCaptor.getValue().getStage());
        assertEquals(AssessmentFlowStatus.DUPLICATE, duplicateCaptor.getValue().getStatus());
        assertEquals(Long.valueOf(9L), duplicateCaptor.getValue().getAssessmentId());
    }

    @Test
    void treatsUniqueViolationAsAlreadyCommittedWhenRowExists() {
        UploadConfirmDto task = completeTask();
        when(assessments.findBySourceTaskId(task.getTaskId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(Assessment.builder().id(9L).projectId(PROJECT_ID).build()));
        when(assessments.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        UploadConfirmProcessor.UploadConfirmOutcome outcome = processor.process(task);

        assertTrue(outcome.isAlreadyCompleted());
        assertEquals(Long.valueOf(9L), outcome.getAssessmentId());
        verify(files, never()).save(any(ProjectFile.class));
        verify(dispatcher, never()).dispatch(any(), any());
    }

    @Test
    void rethrowsUniqueViolationWhenNoExistingRow() {
        UploadConfirmDto task = completeTask();
        when(assessments.findBySourceTaskId(task.getTaskId())).thenReturn(Optional.empty());
        when(assessments.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThrows(DataIntegrityViolationException.class, () -> processor.process(task));
    }

    @Test
    void resolvesLegacyTaskFilesFromRedisMetadata() throws Exception {
        Path chunkDir = chunkDirectory("upload-1", "legacy-content");
        UploadFileDto upload = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("upload-1").userId(8L)
                .filePath(chunkDir.toString()).totalChunks(1).fileSuffix("pdf").build();
        Map<Object, Object> uploads = new HashMap<>();
        uploads.put("upload-1", upload);
        when(redisUtil.hmget(any())).thenReturn(uploads);
        when(assessments.findBySourceTaskId(any())).thenReturn(Optional.empty());
        when(assessments.saveAndFlush(any())).thenAnswer(invocation -> {
            Assessment assessment = invocation.getArgument(0);
            assessment.setId(12L);
            return assessment;
        });
        when(files.save(any(ProjectFile.class))).thenAnswer(invocation -> {
            ProjectFile projectFile = invocation.getArgument(0);
            projectFile.setId(102L);
            return projectFile;
        });
        UploadConfirmDto legacy = UploadConfirmDto.builder()
                .projectId(PROJECT_ID).userId(8L).build();

        UploadConfirmProcessor.UploadConfirmOutcome outcome = processor.process(legacy);

        assertFalse(outcome.isAlreadyCompleted());
        String derivedTaskId = StringUtils.deriveUploadTaskId(PROJECT_ID,
                Collections.singletonList("upload-1"));
        ArgumentCaptor<Assessment> assessmentCaptor = ArgumentCaptor.forClass(Assessment.class);
        verify(assessments).saveAndFlush(assessmentCaptor.capture());
        assertEquals(derivedTaskId, assessmentCaptor.getValue().getSourceTaskId());
        verify(dispatcher).dispatch(any(), any());
    }

    @Test
    void mergeFailurePropagatesAndAbortsBusiness() {
        UploadFileDto broken = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("upload-2").userId(8L)
                .filePath(Constants.getTempFileDirPath(PROJECT_ID, "missing-dir"))
                .totalChunks(1).fileSuffix("pdf").build();
        UploadConfirmDto task = UploadConfirmDto.builder()
                .taskId("upload:" + PROJECT_ID + ":abc").projectId(PROJECT_ID).userId(8L)
                .files(Collections.singletonList(broken)).build();
        when(assessments.findBySourceTaskId(task.getTaskId())).thenReturn(Optional.empty());
        when(assessments.saveAndFlush(any())).thenAnswer(invocation -> {
            Assessment assessment = invocation.getArgument(0);
            assessment.setId(9L);
            return assessment;
        });

        assertThrows(BusinessException.class, () -> processor.process(task));
        verify(dispatcher, never()).dispatch(any(), any());
    }

    private UploadConfirmDto completeTask() {
        UploadFileDto upload = UploadFileDto.builder()
                .projectId(PROJECT_ID).uploadId("upload-1").userId(8L)
                .filePath(Constants.getTempFileDirPath(PROJECT_ID, "upload-1"))
                .totalChunks(1).fileSuffix("pdf").build();
        return UploadConfirmDto.builder()
                .taskId("upload:" + PROJECT_ID + ":abc").projectId(PROJECT_ID).userId(8L)
                .files(Arrays.asList(upload)).build();
    }

    private Path chunkDirectory(String uploadId, String content) throws Exception {
        Path dir = Paths.get(Constants.getTempFileDirPath(PROJECT_ID, uploadId));
        Files.createDirectories(dir);
        Files.write(dir.resolve("0"), content.getBytes("UTF-8"));
        return dir;
    }
}
