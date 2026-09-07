package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.po.file.ProjectFile;
import com.riskwarning.processing.repository.ProjectFileRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceDocumentScopeValidatorTest {

    private final ProjectFileRepository repository = mock(ProjectFileRepository.class);
    private final SourceDocumentScopeValidator validator = new SourceDocumentScopeValidator(repository);
    private final AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");

    @Test
    void acceptsOnlyStoredDocumentOwnedByAssessmentAndProject() {
        SourceDocumentRef document = new SourceDocumentRef(101L, "source.pdf");
        when(repository.findByIdAndProjectIdAndAssessmentId(101L, 10L, 20L))
                .thenReturn(Optional.of(ProjectFile.builder().id(101L).projectId(10L)
                        .assessmentId(20L).filePath("source.pdf").build()));

        validator.validate(scope, Collections.singletonList(document));
    }

    @Test
    void rejectsWrongScopePathAndDuplicateDocument() {
        SourceDocumentRef document = new SourceDocumentRef(101L, "source.pdf");
        when(repository.findByIdAndProjectIdAndAssessmentId(101L, 10L, 20L))
                .thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(scope, Collections.singletonList(document)));

        when(repository.findByIdAndProjectIdAndAssessmentId(101L, 10L, 20L))
                .thenReturn(Optional.of(ProjectFile.builder().id(101L).projectId(10L)
                        .assessmentId(20L).filePath("stored.pdf").build()));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(scope, Collections.singletonList(document)));

        SourceDocumentRef stored = new SourceDocumentRef(101L, "stored.pdf");
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(scope, Arrays.asList(stored, stored)));
    }
}
