package com.riskwarning.processing.analysis;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.processing.dto.analysis.*;
import java.time.LocalDateTime;
import java.util.*;

/** 独立工程样本，不依赖虚拟企业 PDF 或生产指标 ID。 */
public final class ComplianceTestData {
    public static final AnalysisScope SCOPE = new AnalysisScope(1L, 2L, "run");
    public static final EvidenceChunk EVIDENCE = EvidenceChunk.create(3L, 1L, 2L, "sample.txt", 1, 0,
            null, null, "软件许可记录齐全，平均响应时间为 0 天。", LocalDateTime.of(2026, 9, 30, 10, 0));
    public static BehaviorAnalysisContextVO context() {
        return BehaviorAnalysisContextVO.builder().behaviorId("b").description("事实").sourceDocumentId(3L)
                .evidenceIds(Collections.singletonList(EVIDENCE.getId())).snapshotAvailable(true).evidenceComplete(true)
                .readyForAnalysis(true).embeddingModel("embed").retrievalVersion("v1")
                .retrievalStatus("SUCCESS").analysisStatus("WAITING_ANALYSIS")
                .indicatorCandidates(Arrays.asList(candidate("binary", "INDICATOR"), candidate("range", "INDICATOR")))
                .regulationCandidates(Collections.singletonList(candidate("reg", "REGULATION"))).build();
    }
    public static RetrievalCandidateVO candidate(String id, String type) {
        return RetrievalCandidateVO.builder().candidateId(id).candidateType(type).name("候选" + id).content("受控依据")
                .contentAvailable(true).snapshotAvailable(true).behaviorId("b").assessmentId(2L).analysisRunId("run")
                .embeddingModel("embed").embeddingVersion("v1").build();
    }
    public static Map<String, RuleInputRequirement> rules() {
        Map<String, RuleInputRequirement> r = new TreeMap<>();
        r.put("binary", RuleInputRequirement.builder().ruleType("BINARY").condition("许可是否缺失").build());
        r.put("range", RuleInputRequirement.builder().ruleType("RANGE").inputUnit("day").observation("平均响应时间").build());
        return r;
    }
    public static ObjectNode response() {
        ObjectNode n = new ObjectMapper().createObjectNode();
        n.put("schemaVersion", "1.1"); n.put("indicatorId", "binary");
        n.putArray("evidenceIds").add(EVIDENCE.getId()); n.putArray("regulationIds");
        n.put("applicable", "APPLICABLE"); n.put("complianceStatus", "COMPLIANT");
        n.put("enterpriseFact", "许可记录齐全"); n.put("reasoning", "依据当前记录作出判断");
        n.put("confidence", 0.8); n.put("conditionMet", false);
        return n;
    }
    private ComplianceTestData() { }
}
