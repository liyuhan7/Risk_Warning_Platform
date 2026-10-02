package com.riskwarning.processing.analysis;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.rule.RuleCanonicalJson;
import java.util.*;

/** 行为输入的稳定指纹，不包含向量和入库时间等存储信息。 */
public final class BehaviorSnapshotFingerprint {
    public static String hash(AnalysisScope scope, List<Behavior> behaviors) {
        Map<String, Object> envelope = new TreeMap<>(); envelope.put("scope", scope);
        Map<String, Object> inputs = new TreeMap<>();
        for (Behavior behavior : behaviors) {
            Map<String, Object> input = new TreeMap<>();
            input.put("schemaVersion", behavior.getSchemaVersion()); input.put("sourceDocumentId", behavior.getSourceDocumentId());
            input.put("description", behavior.getDescription()); input.put("subject", behavior.getSubject());
            input.put("action", behavior.getAction()); input.put("object", behavior.getObject());
            input.put("type", behavior.getType()); input.put("dimension", behavior.getDimension()); input.put("status", behavior.getStatus());
            input.put("quantitativeData", behavior.getQuantitativeData()); input.put("quantitativeUnit", behavior.getQuantitativeUnit());
            input.put("behaviorDate", behavior.getBehaviorDate()); input.put("confidence", behavior.getConfidence());
            input.put("evidenceIds", sorted(behavior.getEvidenceIds())); input.put("tags", sorted(behavior.getTags()));
            input.put("extractionModel", behavior.getExtractionModel()); input.put("extractionPromptVersion", behavior.getExtractionPromptVersion());
            if (inputs.put(behavior.getId(), input) != null) throw new IllegalArgumentException("行为输入包含重复身份");
        }
        envelope.put("behaviors", inputs); return RuleCanonicalJson.hash(envelope);
    }
    private static List<String> sorted(List<String> values) {
        if (values == null) return null;
        List<String> copy = new ArrayList<>(values);
        if (copy.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("行为引用或标签不能为空");
        Collections.sort(copy); return copy;
    }
    private BehaviorSnapshotFingerprint() { }
}
