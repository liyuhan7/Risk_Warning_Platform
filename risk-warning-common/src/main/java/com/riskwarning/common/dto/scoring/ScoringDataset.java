package com.riskwarning.common.dto.scoring;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 启动时载入单一版本的业务依据；运行中不混读变化的四份文件。 */
public class ScoringDataset {
    private static final String[] FILES = {"indicator.json", "regulation.json",
            "reference/indicator-regulation-relations.json", "reference/references.json"};
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, JsonNode> indicators;
    private final Map<String, JsonNode> regulations;
    private final Map<String, JsonNode> references;
    private final Map<String, List<JsonNode>> relations = new TreeMap<>();
    private final Map<String, String> fileHashes = new TreeMap<>();
    private final String version;

    public ScoringDataset(String directory) throws IOException {
        Path root = Paths.get(directory).toAbsolutePath().normalize();
        Map<String, JsonNode> documents = new HashMap<>();
        for (String file : FILES) {
            byte[] bytes = Files.readAllBytes(root.resolve(file));
            fileHashes.put(file, ScoringJson.sha256(bytes));
            documents.put(file, mapper.readTree(bytes));
        }
        // 读取期间发生变化时拒绝发布混合版本。
        for (String file : FILES)
            check(fileHashes.get(file).equals(ScoringJson.sha256(Files.readAllBytes(root.resolve(file)))), "读取期间依据文件发生变化");
        indicators = index(documents.get(FILES[0]), "id");
        regulations = index(documents.get(FILES[1]), "id");
        references = index(documents.get(FILES[3]).get("regulations"), "regulationId");
        for (String regulation : references.keySet()) check(regulations.containsKey(regulation), "核对来源引用不存在的法规");
        JsonNode associations = documents.get(FILES[2]);
        check(associations.isArray(), "指标法规关系必须是数组");
        Set<String> identities = new HashSet<>();
        for (JsonNode relation : associations) {
            String indicatorId = relation.path("indicatorId").asText();
            String regulationId = relation.path("regulationId").asText();
            check(indicators.containsKey(indicatorId) && regulations.containsKey(regulationId), "指标法规关系引用不存在");
            check(identities.add(indicatorId + ":" + regulationId), "指标法规关系重复");
            check(text(relation.path("supportingText").asText()) && text(relation.path("applicability").asText()), "关系缺少支持文本或适用条件");
            relations.computeIfAbsent(indicatorId, ignored -> new ArrayList<>()).add(relation.deepCopy());
        }
        relations.values().forEach(items -> items.sort(Comparator.comparing(item -> item.path("regulationId").asText())));
        version = ScoringJson.hash(fileHashes);
    }

    public Map<String, Object> manifest() {
        Map<String, Object> out = new TreeMap<>();
        out.put("datasetVersion", version);
        out.put("fileHashes", new TreeMap<>(fileHashes));
        out.put("indicatorCount", indicators.size());
        out.put("regulationCount", regulations.size());
        out.put("relationCount", relations.values().stream().mapToInt(List::size).sum());
        out.put("referenceCount", references.size());
        return out;
    }

    public Set<String> relatedIndicatorIds() { return Collections.unmodifiableSet(new TreeSet<>(relations.keySet())); }

    /** 返回该指标的全部登记关系，不依赖向量检索返回数量。 */
    public ScoringSourceBundle bundle(String datasetVersion, String indicatorId) {
        check(version.equals(datasetVersion), "依据数据集版本不一致，禁止用当前版本替代历史依据");
        check(indicators.containsKey(indicatorId), "评分指标不存在");
        ScoringSourceBundle bundle = new ScoringSourceBundle();
        bundle.setDatasetVersion(version);
        bundle.setIndicator(indicators.get(indicatorId).deepCopy());
        List<ScoringSourceBundle.Source> sources = new ArrayList<>();
        for (JsonNode relation : relations.getOrDefault(indicatorId, Collections.emptyList())) {
            String id = relation.path("regulationId").asText();
            JsonNode regulation = regulations.get(id);
            ScoringSourceBundle.Source source = new ScoringSourceBundle.Source();
            source.setRegulationId(id);
            source.setRegulation(regulation.deepCopy());
            source.setRelation(relation.deepCopy());
            source.setReference(references.containsKey(id) ? references.get(id).deepCopy() : NullNode.instance);
            source.setFullTextHash(ScoringJson.sha256(regulation.path("fullText").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            source.setRelationHash(ScoringJson.hash(relation));
            source.setReferenceSourcesHash(ScoringJson.hash(source.getReference()));
            sources.add(source);
        }
        bundle.setRelations(sources);
        return bundle;
    }

    private Map<String, JsonNode> index(JsonNode items, String key) {
        check(items != null && items.isArray(), "依据文件结构无效");
        Map<String, JsonNode> out = new TreeMap<>();
        for (JsonNode item : items) {
            String id = item.path(key).asText();
            check(text(id) && out.put(id, item.deepCopy()) == null, "依据编号缺失或重复");
        }
        return out;
    }

    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
