package com.riskwarning.processing.service.scoring.fact;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.processing.rule.v2.ScoringRuleRegistry;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.*;

/** 发布阶段登记的语义绑定；运行时不通过文本相似度猜测同义关系。 */
@Component
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class SharedFactCatalog {
    private final Document document;
    private final String hash;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

    public SharedFactCatalog(BehaviorFactProperties properties) {
        try (InputStream stream = new DefaultResourceLoader().getResource(properties.getFactCatalog()).getInputStream()) {
            document = mapper.readValue(stream, Document.class);
            hash = ScoringJson.hash(document);
        } catch (Exception error) { throw new IllegalArgumentException("共享事实目录不可用", error); }
    }

    public String version() { return document.getCatalogVersion(); }
    public String hash() { return hash; }
    public List<Binding> bindings() { return mapper.convertValue(document.getBindings(), mapper.getTypeFactory().constructCollectionType(List.class, Binding.class)); }
    public Definition definition(String key) { return mapper.convertValue(document.getDefinitions().get(key), Definition.class); }

    public void validate(ScoringRuleRegistry registry) {
        require(registry.version().equals(document.getRegistryVersion()) && registry.contentHash().equals(document.getRulesHash()), "事实目录与规则版本不一致");
        Set<String> expected = new TreeSet<>();
        Map<String, String> types = new HashMap<>();
        for (String id : registry.indicatorIds()) if (registry.isEnabled(id)) {
            for (Requirement requirement : registry.definition(id).getRequirements()) {
                String gate = key(id, requirement.getRequirementId(), null);
                expected.add(gate); types.put(gate, "APPLICABILITY|");
                List<Condition> leaves = leaves(requirement.getCondition());
                if (requirement.getExceptWhen() != null) leaves.addAll(leaves(requirement.getExceptWhen()));
                for (Condition condition : leaves) {
                    String entry = key(id, requirement.getRequirementId(), condition.getConditionId());
                    expected.add(entry);
                    types.put(entry, (condition.getValueType() == null ? requirement.getValueType() : condition.getValueType()).name() + "|" + Objects.toString(condition.getUnit(), ""));
                }
            }
        }
        Set<String> actual = new TreeSet<>();
        for (Binding binding : document.getBindings()) {
            String entry = key(binding.getIndicatorId(), binding.getRequirementId(), binding.getConditionId());
            require(actual.add(entry), "事实绑定重复");
            Definition definition = document.getDefinitions().get(binding.getFactKey());
            require(definition != null && definition.getText() != null && !definition.getText().trim().isEmpty()
                    && definition.getScope() != null && !definition.getScope().trim().isEmpty(), "事实定义缺失");
            require(Objects.equals(types.get(entry), definition.getType() + "|" + Objects.toString(definition.getUnit(), "")), "事实绑定类型或单位不一致");
        }
        require(expected.equals(actual), "事实绑定未完整覆盖已启用要求");
    }

    public static List<Condition> leaves(Condition root) {
        List<Condition> out = new ArrayList<>();
        if (root.getConditionId() != null) out.add(root);
        if (root.getAllOf() != null) root.getAllOf().forEach(child -> out.addAll(leaves(child)));
        if (root.getAnyOf() != null) root.getAnyOf().forEach(child -> out.addAll(leaves(child)));
        if (root.getNotOf() != null) out.addAll(leaves(root.getNotOf()));
        return out;
    }

    private static String key(String indicator, String requirement, String condition) { return indicator + "/" + requirement + "/" + Objects.toString(condition, "gate"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    @Data
    public static class Document {
        private String catalogVersion;
        private String registryVersion;
        private String rulesHash;
        private Map<String, Definition> definitions;
        private List<Binding> bindings;
    }
}
