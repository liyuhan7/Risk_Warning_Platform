package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.analysis.RetrievalAuditStatus;
import com.riskwarning.common.po.analysis.RetrievalAudit;
import com.riskwarning.processing.repository.RetrievalAuditRepository;
import com.riskwarning.processing.analysis.*;
import com.riskwarning.processing.service.*;
import com.riskwarning.processing.dto.analysis.AnalysisContextVO;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class RuleSourceSnapshotServiceTest {
    private final RetrievalAuditRepository audits = mock(RetrievalAuditRepository.class);
    private final RuleSourceSnapshotService service = new RuleSourceSnapshotService(audits,load(rule("binary")));

    @Test void persistedSourceIsStableAcrossBehaviorsAndReturnedCopiesDoNotAlterAudit() {
        RetrievalAudit a = audit("a",source("binary")), b = audit("b",source("binary")); rows(a,b);
        Map<String,JsonNode> frozen = service.sources(SCOPE);
        assertEquals(1,frozen.size()); assertEquals(source("binary"),frozen.get("binary"));
        ((ObjectNode) frozen.get("binary")).put("maxScore",9);
        assertEquals(2,a.getCandidates().get(0).getSourceRule().path("maxScore").asInt());
        assertEquals(2,service.sources(SCOPE).get("binary").path("maxScore").asInt());
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("foreign",source("binary")));
    }
    @Test void enabledRulesCannotUseMissingOrDriftedSource() {
        rows(audit("a",null)); assertThrows(RuleContractException.class, () -> service.sources(SCOPE));
        ObjectNode drift = source("binary"); drift.put("maxScore",3);
        rows(audit("a",drift)); assertEquals("SOURCE_RULE_HASH_MISMATCH",assertThrows(RuleContractException.class, () -> service.sources(SCOPE)).getCode());
    }
    @Test void auditAndCandidateScopeAndIdentityAreChecked() {
        RetrievalAudit a = audit("a",source("binary")); a.setProjectId(3L); rows(a);
        assertThrows(RuleContractException.class, () -> service.sources(SCOPE));
        a.setProjectId(1L); a.getCandidates().get(0).getResult().setAnalysisRunId("foreign");
        assertThrows(RuleContractException.class, () -> service.sources(SCOPE));
        a.getCandidates().get(0).getResult().setAnalysisRunId("run"); rows(a,a);
        assertThrows(RuleContractException.class, () -> service.sources(SCOPE));
    }
    @Test void inconsistentUnregisteredSourcesAreRejectedAndLegacyUnregisteredNullIsAllowed() {
        RuleSourceSnapshotService empty = new RuleSourceSnapshotService(audits,VerifiedRuleRegistry.fromJson("{\"schemaVersion\":\"1.0\",\"registryVersion\":\"v\",\"purpose\":\"PRODUCTION\",\"rules\":{}}",false));
        rows(audit("a",null)); assertTrue(empty.sources(SCOPE).isEmpty());
        ObjectNode changed = source("binary"); changed.put("maxScore",9);
        rows(audit("a",source("binary")),audit("b",changed));
        assertThrows(RuleContractException.class, () -> empty.sources(SCOPE));
    }
    @Test void productionAnalysisOverloadReadsCommittedAuditSources() {
        rows(audit("b",source("binary")));
        AnalysisContextService contexts = mock(AnalysisContextService.class); ComplianceAnalysisService analysis = mock(ComplianceAnalysisService.class);
        when(contexts.context(2L,1L,"run")).thenReturn(AnalysisContextVO.builder().projectId(1L).assessmentId(2L).analysisRunId("run")
                .items(Collections.singletonList(ComplianceTestData.context())).build());
        RegisteredComplianceAnalysisService wrapper = new RegisteredComplianceAnalysisService(load(rule("binary")),contexts,analysis,service);
        wrapper.analyze(SCOPE,"b",Collections.emptyMap());
        verify(audits).findByAssessmentIdAndAnalysisRunId(2L,"run");
        verify(analysis).analyze(eq(SCOPE),eq("b"),anyMap(),eq(Collections.emptyMap()));
    }
    @Test void unavailableSnapshotsDoNotMasqueradeAsCurrentRuleSources() {
        RetrievalAudit a = audit("a",null); a.setRetrievalStatus(RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE); rows(a);
        assertTrue(service.sources(SCOPE).isEmpty());
    }
    private void rows(RetrievalAudit... rows) { when(audits.findByAssessmentIdAndAnalysisRunId(2L,"run")).thenReturn(Arrays.asList(rows)); }
    private static RetrievalAudit audit(String behavior, JsonNode source) {
        RetrievalResult result = RetrievalResult.builder().candidateType(RetrievalCandidateType.INDICATOR).candidateId("binary")
                .behaviorId(behavior).assessmentId(2L).analysisRunId("run").build();
        return RetrievalAudit.builder().projectId(1L).assessmentId(2L).analysisRunId("run").behaviorId(behavior)
                .retrievalStatus(RetrievalAuditStatus.SUCCESS)
                .candidates(Collections.singletonList(RetrievalCandidateSnapshot.builder().result(result).sourceRule(source).build())).build();
    }
}
