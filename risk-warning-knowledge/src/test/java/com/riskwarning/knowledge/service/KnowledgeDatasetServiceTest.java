package com.riskwarning.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.scoring.ScoringSourceBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeDatasetServiceTest {
    @TempDir Path root;

    private void fixture() throws Exception {
        Files.createDirectories(root.resolve("reference"));
        write("indicator.json", "[{\"id\":\"i\",\"name\":\"测试指标\"}]");
        write("regulation.json", "[{\"id\":\"r1\",\"fullText\":\"完整原文一\"},{\"id\":\"r2\",\"fullText\":\"完整原文二\"}]");
        write("reference/indicator-regulation-relations.json", "[{\"indicatorId\":\"i\",\"regulationId\":\"r2\",\"supportingText\":\"二\",\"applicability\":\"条件二\"},{\"indicatorId\":\"i\",\"regulationId\":\"r1\",\"supportingText\":\"一\",\"applicability\":\"条件一\"}]");
        write("reference/references.json", "{\"regulations\":[{\"regulationId\":\"r1\",\"sources\":[{\"title\":\"核对依据\"}]}]}");
    }
    private void write(String file, String text) throws Exception { Files.write(root.resolve(file), text.getBytes(StandardCharsets.UTF_8)); }

    @Test void supplementsAllRelationsAndRetainsTextAndMissingIndependentReference() throws Exception {
        fixture();
        KnowledgeDatasetService service = new KnowledgeDatasetService(root.toString());
        String version = (String)service.manifest().get("datasetVersion");
        ScoringSourceBundle bundle = service.bundle(version, "i");
        assertEquals(2, bundle.getRelations().size());
        assertEquals("r1", bundle.getRelations().get(0).getRegulationId());
        assertEquals("完整原文二", bundle.getRelations().get(1).getRegulation().path("fullText").asText());
        assertTrue(bundle.getRelations().get(1).getReference().isNull());
        assertEquals("核对依据", bundle.getRelations().get(0).getReference().path("sources").get(0).path("title").asText());
        assertThrows(IllegalArgumentException.class, () -> service.bundle("old-version", "i"));
        assertThrows(IllegalArgumentException.class, () -> service.bundle(version, "missing"));
    }

    @Test void byteChangesCreateNewVersionAndOldInMemoryBundleRemainsStable() throws Exception {
        fixture();
        KnowledgeDatasetService old = new KnowledgeDatasetService(root.toString());
        String version = (String)old.manifest().get("datasetVersion");
        String hash = old.bundle(version, "i").bundleHash();
        write("indicator.json", "[ {\"id\":\"i\",\"name\":\"测试指标\"}]");
        KnowledgeDatasetService next = new KnowledgeDatasetService(root.toString());
        assertNotEquals(version, next.manifest().get("datasetVersion"));
        assertEquals(hash, old.bundle(version, "i").bundleHash());
        ((com.fasterxml.jackson.databind.node.ObjectNode)old.bundle(version, "i").getIndicator()).put("name", "被修改");
        assertEquals(hash, old.bundle(version, "i").bundleHash());
        assertThrows(IllegalArgumentException.class, () -> next.bundle(version, "i"));
    }

    @Test void danglingAndDuplicateReferencesAndDuplicateJsonKeysFailBeforePublishing() throws Exception {
        fixture();
        write("regulation.json", "[{\"id\":\"r1\",\"fullText\":\"一\"}]");
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeDatasetService(root.toString()));
        fixture();
        write("indicator.json", "[{\"id\":\"i\",\"id\":\"duplicate\"}]");
        assertThrows(Exception.class, () -> new KnowledgeDatasetService(root.toString()));
    }

    @Test void actualLibraryRelationsAreFullyReadableWithoutSearchOrModel() throws Exception {
        Path actual = Paths.get("documents/data");
        if (!Files.exists(actual)) actual = Paths.get("../documents/data");
        KnowledgeDatasetService service = new KnowledgeDatasetService(actual.toString());
        String version = (String)service.manifest().get("datasetVersion");
        com.fasterxml.jackson.databind.JsonNode relations = new ObjectMapper().readTree(actual.resolve("reference/indicator-regulation-relations.json").toFile());
        Map<String, Set<String>> expected = new HashMap<>();
        relations.forEach(row -> expected.computeIfAbsent(row.path("indicatorId").asText(), id -> new HashSet<>()).add(row.path("regulationId").asText()));
        int read = 0;
        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            ScoringSourceBundle bundle = service.bundle(version, entry.getKey());
            Set<String> found = new HashSet<>();
            bundle.getRelations().forEach(source -> { found.add(source.getRegulationId()); assertFalse(source.getRegulation().path("fullText").asText().trim().isEmpty()); });
            assertEquals(entry.getValue(), found);
            read += found.size();
        }
        assertEquals(relations.size(), read);
        assertEquals(relations.size(), service.manifest().get("relationCount"));
    }
}
