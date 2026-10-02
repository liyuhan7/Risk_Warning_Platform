package com.riskwarning.processing.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.processing.client.ClassifierClient;
import com.riskwarning.processing.repository.ElasticsearchBehaviorDocumentRepository;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 仅用于独立空 ES：验证无描述向量的分类事实落库、作用域查询和幂等写入。 */
@EnabledIfSystemProperty(named = "analysis.behavior.persistence.test", matches = "true")
class StructuredBehaviorPersistenceIntegrationTest {
    @Test void classifiedFactsRemainQueryableWithoutLegacyVectors() throws Exception {
        String endpoint = System.getenv("ANALYSIS_TEST_ES_URL");
        assertNotNull(endpoint, "必须显式提供独立测试 ES，不能默认连接开发数据库");
        RestClientTransport transport = new RestClientTransport(RestClient.builder(HttpHost.create(endpoint)).build(),
                new JacksonJsonpMapper());
        ElasticsearchClient es = new ElasticsearchClient(transport);
        boolean created = false;
        try {
            assertFalse(es.indices().exists(r -> r.index("t_behavior")).value(), "仅接受空 ES，已有业务索引不能用于此测试");
            Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            if (!Files.exists(root.resolve("documents/es_mappings.json"))) root = root.getParent();
            String mapping = new ObjectMapper().readTree(Files.readAllBytes(root.resolve("documents/es_mappings.json")))
                    .get("t_behavior").toString();
            es.indices().create(r -> r.index("t_behavior").withJson(new StringReader(mapping)));
            created = true;
            ClassifierClient classifier = mock(ClassifierClient.class);
            when(classifier.classifyBatch(any())).thenReturn(classification());
            ElasticsearchBehaviorDocumentRepository repository = new ElasticsearchBehaviorDocumentRepository(es);
            StructuredBehaviorWriter writer = new StructuredBehaviorWriter(repository, classifier);
            Behavior fact = fact("fact");
            writer.enrichAndWrite(Collections.singletonList(fact));
            writer.enrichAndWrite(Collections.singletonList(fact));

            assertEquals(1L, es.count(r -> r.index("t_behavior")).count());
            List<Behavior> stored = repository.findByScope(1L, 2L, "run");
            assertEquals(1, stored.size());
            assertEquals("fact", stored.get(0).getId());
            assertEquals("定性", stored.get(0).getType());
            assertEquals("治理", stored.get(0).getDimension());
            assertNull(stored.get(0).getDescriptionVector());
            assertEquals("企业完成审查", new BehaviorQueryTextBuilder().buildQueryText(stored.get(0)));
            assertTrue(repository.findByScope(1L, 2L, "other-run").isEmpty());

            when(classifier.classifyBatch(any())).thenThrow(new IllegalStateException("classification unavailable"));
            assertThrows(IllegalStateException.class, () -> writer.enrichAndWrite(Collections.singletonList(fact("rejected"))));
            assertEquals(1L, es.count(r -> r.index("t_behavior")).count());
        } finally {
            try {
                // 只清理本测试在空 ES 中创建的合成数据，不删除既有索引。
                if (created) es.indices().delete(r -> r.index("t_behavior"));
            } finally {
                transport.close();
            }
        }
    }

    private Behavior fact(String id) {
        return Behavior.builder().id(id).projectId(1L).assessmentId(2L).analysisRunId("run")
                .sourceDocumentId(3L).description("企业完成审查").build();
    }

    private Map<String, Object> classification() {
        Map<String, Object> item = new HashMap<>();
        item.put("tags", Collections.singletonList("供应链"));
        item.put("type", "定性"); item.put("dimension", "治理");
        return Collections.singletonMap("results", Collections.singletonList(item));
    }
}
