package com.riskwarning.processing.rule;

import com.riskwarning.processing.analysis.*;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Collections;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class RegisteredComplianceAnalysisServiceTest {
    @Test void bindsOnlyRegisteredCurrentCandidateRequirements() {
        AnalysisContextService contexts = mock(AnalysisContextService.class); ComplianceAnalysisService analysis = mock(ComplianceAnalysisService.class);
        when(contexts.context(2L,1L,"run")).thenReturn(AnalysisContextVO.builder().projectId(1L).assessmentId(2L).analysisRunId("run")
                .items(Collections.singletonList(ComplianceTestData.context())).build());
        RegisteredComplianceAnalysisService service = new RegisteredComplianceAnalysisService(load(rule("binary")),contexts,analysis,mock(RuleSourceSnapshotService.class));
        service.analyze(SCOPE,"b",Collections.singletonMap("binary",source("binary")),Collections.emptyMap());
        ArgumentCaptor<Map> requirements = ArgumentCaptor.forClass(Map.class);
        verify(analysis).analyze(eq(SCOPE),eq("b"),requirements.capture(),eq(Collections.emptyMap()));
        assertEquals(1,requirements.getValue().size());
        RuleInputRequirement r = (RuleInputRequirement)requirements.getValue().get("binary");
        assertEquals("BINARY",r.getRuleType()); assertEquals("是否缺少许可",r.getCondition());
        assertFalse(requirements.getValue().containsKey("range"));
    }
    @Test void absentOrDriftedSourceStopsBeforeModelAnalysis() {
        AnalysisContextService contexts = mock(AnalysisContextService.class); ComplianceAnalysisService analysis = mock(ComplianceAnalysisService.class);
        when(contexts.context(2L,1L,"run")).thenReturn(AnalysisContextVO.builder().projectId(1L).assessmentId(2L).analysisRunId("run")
                .items(Collections.singletonList(ComplianceTestData.context())).build());
        RegisteredComplianceAnalysisService service = new RegisteredComplianceAnalysisService(load(rule("binary")),contexts,analysis,mock(RuleSourceSnapshotService.class));
        assertThrows(RuleContractException.class, () -> service.analyze(SCOPE,"b",Collections.emptyMap(),Collections.emptyMap()));
        com.fasterxml.jackson.databind.node.ObjectNode source = source("binary"); source.put("maxScore",99);
        assertThrows(RuleContractException.class, () -> service.analyze(SCOPE,"b",Collections.singletonMap("binary",source),Collections.emptyMap()));
        verifyNoInteractions(analysis);
    }
}
