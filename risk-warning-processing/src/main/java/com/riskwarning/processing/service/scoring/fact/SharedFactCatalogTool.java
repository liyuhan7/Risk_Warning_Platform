package com.riskwarning.processing.service.scoring.fact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import com.riskwarning.common.dto.scoring.ScoringJson;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.processing.rule.v2.ScoringRuleRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 离线登记完整事实绑定；保留既有语义绑定，其余仅合并定义完全相同的事实。 */
public final class SharedFactCatalogTool {
    private static final String SCOPE = "仅针对本次行为及关联原文中的同一主体、对象和期间；对象、期间或口径不清时返回未知，不混用其他记录。";
    private SharedFactCatalogTool() { }

    public static SharedFactCatalog.Document expand(ScoringRuleRegistry registry, ScoringRuleRegistry seedRegistry, SharedFactCatalog seed) {
        seed.validate(seedRegistry);
        Map<String, Binding> registered = new HashMap<>();
        for (Binding binding : seed.bindings()) registered.put(identity(binding), binding);
        SharedFactCatalog.Document out = new SharedFactCatalog.Document();
        out.setCatalogVersion("business-facts-" + registry.version());
        out.setRegistryVersion(registry.version()); out.setRulesHash(registry.contentHash());
        out.setDefinitions(new TreeMap<>()); out.setBindings(new ArrayList<>());
        Map<String, String> definitions = new HashMap<>();
        for (String indicator : registry.indicatorIds()) if (registry.isEnabled(indicator)) {
            for (Requirement requirement : registry.definition(indicator).getRequirements()) {
                List<Condition> leaves = new ArrayList<>(); leaves.add(null);
                leaves.addAll(SharedFactCatalog.leaves(requirement.getCondition()));
                if (requirement.getExceptWhen() != null) leaves.addAll(SharedFactCatalog.leaves(requirement.getExceptWhen()));
                for (Condition condition : leaves) {
                    Binding binding = new Binding(); binding.setIndicatorId(indicator); binding.setRequirementId(requirement.getRequirementId());
                    binding.setConditionId(condition == null ? null : condition.getConditionId());
                    Binding previous = registered.get(identity(binding));
                    Definition definition;
                    if (previous != null) {
                        Requirement old = seedRegistry.definition(indicator).getRequirements().stream()
                                .filter(item -> item.getRequirementId().equals(requirement.getRequirementId())).findFirst().get();
                        if (!Objects.equals(ScoringJson.hash(old), ScoringJson.hash(requirement)))
                            throw new IllegalArgumentException("既有要求发生变化，需重新核对共享绑定：" + identity(binding));
                        binding.setFactKey(previous.getFactKey()); definition = seed.definition(previous.getFactKey());
                    } else {
                        definition = new Definition();
                        definition.setType(condition == null ? "APPLICABILITY" : (condition.getValueType() == null ? requirement.getValueType() : condition.getValueType()).name());
                        definition.setUnit(condition == null ? null : condition.getUnit());
                        definition.setText(condition == null ? requirement.getApplicabilityGate().getText() : condition.getPromptHint());
                        // 同一业务场景中的不同要求可能只有部分适用；适用性绑定保留具体义务范围。
                        String context = condition == null ? "要求范围：" + requirementText(requirement)
                                : "适用范围：" + requirement.getApplicabilityGate().getText();
                        definition.setScope(SCOPE + context);
                        String hash = ScoringJson.hash(definition);
                        binding.setFactKey(definitions.computeIfAbsent(hash, key -> "fact-" + key));
                    }
                    out.getDefinitions().put(binding.getFactKey(), definition);
                    definitions.putIfAbsent(ScoringJson.hash(definition), binding.getFactKey());
                    out.getBindings().add(binding);
                }
            }
        }
        return out;
    }

    private static String identity(Binding binding) {
        return binding.getIndicatorId() + "/" + binding.getRequirementId() + "/" + Objects.toString(binding.getConditionId(), "gate");
    }

    private static String requirementText(Requirement requirement) {
        List<String> texts = new ArrayList<>();
        for (Condition leaf : SharedFactCatalog.leaves(requirement.getCondition())) texts.add(leaf.getPromptHint());
        if (requirement.getExceptWhen() != null)
            for (Condition leaf : SharedFactCatalog.leaves(requirement.getExceptWhen())) texts.add("例外：" + leaf.getPromptHint());
        return String.join("；", texts);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("用法：业务规则文件 既有规则文件 既有事实目录 新事实目录输出文件");
        ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8), true);
        ScoringRuleRegistry seedRegistry = ScoringRuleRegistry.fromJson(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8), true);
        BehaviorFactProperties settings = new BehaviorFactProperties(); settings.setFactCatalog(Paths.get(args[2]).toUri().toString());
        SharedFactCatalog.Document catalog = expand(registry, seedRegistry, new SharedFactCatalog(settings));
        Path output = Paths.get(args[3]);
        Files.write(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(catalog), StandardOpenOption.CREATE_NEW);
        settings.setFactCatalog(output.toUri().toString()); new SharedFactCatalog(settings).validate(registry);
        System.out.println("指标=" + registry.indicatorIds().size() + "，事实定义=" + catalog.getDefinitions().size() + "，条件及适用性绑定=" + catalog.getBindings().size());
    }
}
