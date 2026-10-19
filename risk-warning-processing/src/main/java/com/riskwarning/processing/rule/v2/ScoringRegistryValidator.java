package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.scoring.ScoringDataset;
import com.riskwarning.common.dto.scoring.ScoringSourceBundle;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 离线发布校验入口；无需数据库、ES 或模型服务，不写入和启用规则。 */
public final class ScoringRegistryValidator {
    public static Map<String, Object> validate(Path registryFile, Path datasetDirectory, boolean allowTest) throws Exception {
        ScoringDataset dataset = new ScoringDataset(datasetDirectory.toString());
        ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(new String(Files.readAllBytes(registryFile), StandardCharsets.UTF_8), allowTest);
        String version = (String)dataset.manifest().get("datasetVersion");
        int enabled = 0;
        Set<String> enabledRelations = new HashSet<>();
        int noReference = 0, noAnchor = 0;
        List<Map<String, Object>> review = new ArrayList<>();
        for (String id : registry.indicatorIds()) {
            ScoringSourceBundle source = dataset.bundle(version, id);
            IndicatorRule rule = registry.registeredDefinition(id);
            if (registry.isEnabled(id)) { registry.require(id, source); enabled++; }
            Map<String, ScoringSourceBundle.Source> byLaw = new HashMap<>();
            source.getRelations().forEach(item -> byLaw.put(item.getRegulationId(), item));
            for (Requirement requirement : rule.getRequirements()) {
                ScoringSourceBundle.Source basis = byLaw.get(requirement.getSourceRegulationId());
                if (basis == null) throw new IllegalArgumentException("要求没有对应的指标法规关系");
                if (registry.isEnabled(id)) enabledRelations.add(id + "|" + basis.getRegulationId());
                if (basis.getReference() == null || basis.getReference().isNull()) noReference++;
                if ("定量".equals(basis.getRegulation().path("type").asText()) && basis.getRegulation().path("quantitativeIndicator").isNull()) noAnchor++;
                Map<String, Object> check = new LinkedHashMap<>();
                check.put("indicatorId", id); check.put("requirementId", requirement.getRequirementId());
                check.put("regulationId", requirement.getSourceRegulationId()); check.put("condition", requirement.getCondition());
                check.put("supportingText", basis.getRelation().path("supportingText").asText());
                check.put("reviewReason", requirement.getReviewReason()); check.put("enabled", registry.isEnabled(id)); review.add(check);
            }
        }
        Map<String, Object> report = new TreeMap<>(dataset.manifest());
        report.put("registryVersion", registry.version());
        report.put("registeredIndicators", registry.indicatorIds().size());
        report.put("enabledIndicators", enabled);
        report.put("pendingIndicators", registry.indicatorIds().size() - enabled);
        report.put("enabledRelations", enabledRelations.size()); report.put("requirementsWithoutIndependentReference", noReference);
        report.put("quantitativeSourcesWithoutAnchor", noAnchor); report.put("semanticReviewItems", review);
        report.put("passed", true);
        return report;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || args.length == 3 && !"--allow-test".equals(args[2]))
            throw new IllegalArgumentException("用法：ScoringRegistryValidator 注册表路径 数据目录 [--allow-test]");
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                validate(Paths.get(args[0]), Paths.get(args[1]), args.length == 3)));
    }

    private ScoringRegistryValidator() { }
}
