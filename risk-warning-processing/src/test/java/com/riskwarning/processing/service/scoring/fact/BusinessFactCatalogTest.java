package com.riskwarning.processing.service.scoring.fact;

import com.fasterxml.jackson.databind.*;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.processing.dto.analysis.BehaviorAnalysisContextVO;
import com.riskwarning.processing.rule.v2.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 完整业务目录从共享事实映射至既有评分引擎，沿用独立业务案例的预期分数。 */
class BusinessFactCatalogTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static ScoringRuleRegistry registry;
    private static SharedFactCatalog catalog;
    private static JsonNode cases;
    private static ScoringDataset dataset;
    private static Map<String, List<Binding>> bindings;
    private static final Map<String, ScoringSourceBundle> sources = new HashMap<>();

    @BeforeAll static void load() throws Exception {
        registry = new ScoringRuleRegistry("classpath:rules/business-catalog-test-v2.json", true);
        BehaviorFactProperties properties = new BehaviorFactProperties();
        properties.setFactCatalog("classpath:rules/business-fact-bindings-v1.json"); catalog = new SharedFactCatalog(properties);
        dataset = new ScoringDataset((Files.exists(Paths.get("documents/data")) ? Paths.get("documents/data") : Paths.get("../documents/data")).toString());
        cases = M.readTree(new ClassPathResource("scoring/catalog-business-cases.json").getInputStream());
        bindings = new TreeMap<>();
        for (Binding binding : catalog.bindings()) bindings.computeIfAbsent(binding.getIndicatorId(), key -> new ArrayList<>()).add(binding);
    }

    @Test void completeCatalogUsesMatchingRulesAndCanBeUsedAsItsOwnExtensionBaseline() {
        catalog.validate(registry); assertEquals(650, bindings.size());
        int requirements = 0;
        for (String id : registry.indicatorIds()) {
            requirements += registry.require(id, source(id)).getRequirements().size();
        }
        assertEquals(1362, requirements);
        assertEquals(M.valueToTree(SharedFactCatalogTool.expand(registry, registry, catalog)),
                M.valueToTree(newDocument()));
    }

    @Test void existingScoringProfileLoadsFullCatalogAndBehaviorMode() {
        List<String> propertiesFromProfile = new ArrayList<>();
        try {
            new YamlPropertySourceLoader().load("scoring", new ClassPathResource("application-scoring-test.yml")).forEach(source -> {
                org.springframework.core.env.EnumerablePropertySource<?> values = (org.springframework.core.env.EnumerablePropertySource<?>)source;
                for (String name : values.getPropertyNames()) propertiesFromProfile.add(name + "=" + values.getProperty(name));
            });
        } catch (Exception error) { throw new IllegalStateException(error); }
        new ApplicationContextRunner().withPropertyValues(propertiesFromProfile.toArray(new String[0]))
                .withUserConfiguration(PropertiesConfiguration.class, ScoringRuleRegistry.class, SharedFactCatalog.class).run(context -> {
            assertNull(context.getStartupFailure());
            BehaviorFactProperties properties = context.getBean(BehaviorFactProperties.class);
            assertEquals(BehaviorFactProperties.Mode.BEHAVIOR, properties.getFactMode());
            ScoringRuleRegistry loaded = context.getBean(ScoringRuleRegistry.class);
            assertEquals(650, loaded.indicatorIds().size()); context.getBean(SharedFactCatalog.class).validate(loaded);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BehaviorFactProperties.class)
    static class PropertiesConfiguration { }

    @TestFactory Stream<DynamicTest> businessCasesThroughSharedFacts() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode item : cases) tests.add(DynamicTest.dynamicTest(item.path("name").asText() + " [" + item.path("indicatorId").asText() + "]", () -> evaluate(item)));
        return tests.stream();
    }

    private void evaluate(JsonNode item) {
        String id = item.path("indicatorId").asText();
        AnalysisScope scope = new AnalysisScope(1L, 2L, "business-facts");
        ScoringRunSnapshot snapshot = new ScoringRunSnapshot();
        snapshot.setRuleDefinitions(Collections.singletonMap(id, registry.definition(id)));
        snapshot.setSourceBundles(Collections.singletonMap(id, source(id)));
        snapshot.setIndicatorBehaviors(Collections.singletonMap(id, Collections.singletonList("b")));
        FactExtractionPlan settings = new FactExtractionPlan(); settings.setMode("BEHAVIOR"); settings.setModelVersion("controlled-facts");
        settings.setPromptVersion(BehaviorFactPlanner.PROMPT_VERSION); snapshot.setFactExtractionPlan(settings);
        BehaviorPlan plan = new BehaviorPlan(); plan.setBehaviorId("b"); plan.setBindings(bindings.get(id)); plan.getEvidenceReferences().put("E1", "e");
        settings.getBehaviors().put("b", plan);
        Set<String> excluded = new HashSet<>(); item.path("notApplicableRequirementIds").forEach(row -> excluded.add(row.asText()));
        Map<String, Answer> answers = new TreeMap<>();
        Map<String, Question> questions = new TreeMap<>();
        for (Binding binding : plan.getBindings()) {
            Question question = questions.computeIfAbsent(binding.getFactKey(), key -> {
                Question result = new Question(); result.setId("Q" + (questions.size() + 1)); result.setFactKey(key); result.setDefinition(catalog.definition(key)); return result;
            });
            Answer answer = new Answer(); answer.setQuestionId(question.getId()); answer.setConfidence(BigDecimal.ONE);
            answer.setEvidenceIds(Collections.singletonList("E1")); answer.setReason(item.path("behavior").asText());
            JsonNode fact = binding.getConditionId() == null ? null : item.path("facts").get(binding.getConditionId());
            if (binding.getConditionId() == null) {
                answer.setStatus("KNOWN"); answer.setApplicability(excluded.contains(binding.getRequirementId()) ? "NOT_APPLICABLE" : "APPLICABLE");
            } else if (fact == null || fact.isNull()) { answer.setStatus("NOT_STATED"); answer.setEvidenceIds(Collections.emptyList()); }
            else {
                answer.setStatus("KNOWN");
                if (fact.isBoolean()) answer.setBooleanValue(fact.booleanValue());
                else { answer.setNumberValue(fact.decimalValue()); answer.setUnit(question.getDefinition().getUnit()); }
            }
            Answer previous = answers.put(question.getId(), answer);
            if (previous != null) assertEquals(previous, answer, "同一共享事实的案例观测必须一致");
        }
        plan.setQuestions(new ArrayList<>(questions.values()));
        BehaviorAnalysisContextVO behavior = BehaviorAnalysisContextVO.builder().behaviorId("b").description(item.path("behavior").asText())
                .evidenceIds(Collections.singletonList("e")).build();
        RequirementScoringEngine engine = new RequirementScoringEngine(registry, new UniversalConditionEvaluator());
        AnalysisResult result = new BehaviorFactMapper(engine).map(scope, snapshot, behavior, answers).get(0);
        assertFalse(result.getReasoning().contains("FACTS_DERIVED"));
        for (RequirementFinding finding : result.getRequirementFindings()) {
            assertFalse(finding.getReasoning().contains(finding.getRequirementId()));
            for (String condition : finding.getConditionValues().keySet()) assertFalse(finding.getReasoning().contains(condition));
        }
        ScoringDecision decision = engine.evaluate(scope, id, source(id), Collections.singletonMap("b", Collections.singleton("e")), result.getRequirementFindings(), Collections.emptyMap());
        if (item.path("expectedScore").isNull()) {
            assertEquals(item.path("expectedStatus").asText("NEEDS_REVIEW"), decision.getStatus()); assertNull(decision.getCalculatedScore()); assertNull(decision.getRiskLevel());
        } else {
            assertEquals("DECIDED", decision.getStatus(), decision.getEvaluations().toString());
            assertEquals(item.path("expectedScore").asDouble(), decision.getCalculatedScore().doubleValue(), 0.000001);
            assertEquals(item.path("expectedRisk").asText(), decision.getRiskLevel().name()); assertFalse(decision.isConflict());
        }
    }

    private static ScoringSourceBundle source(String id) {
        return sources.computeIfAbsent(id, key -> dataset.bundle((String)dataset.manifest().get("datasetVersion"), key));
    }

    private SharedFactCatalog.Document newDocument() {
        SharedFactCatalog.Document out = new SharedFactCatalog.Document(); out.setCatalogVersion(catalog.version());
        out.setRegistryVersion(registry.version()); out.setRulesHash(registry.contentHash()); out.setBindings(catalog.bindings());
        out.setDefinitions(new TreeMap<>()); for (Binding binding : catalog.bindings()) out.getDefinitions().put(binding.getFactKey(), catalog.definition(binding.getFactKey()));
        return out;
    }
}
