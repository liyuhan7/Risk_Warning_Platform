package com.riskwarning.knowledge.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.riskwarning.common.config.EmbeddingProviderProperties;
import com.riskwarning.common.po.indicator.Indicator;
import com.riskwarning.common.po.regulation.Regulation;
import com.riskwarning.common.provider.AiEmbeddingProvider;
import com.riskwarning.common.provider.EmbeddingRole;
import com.riskwarning.knowledge.config.RetrievalProperties;
import com.riskwarning.knowledge.dto.RetrievalBackfillRequest;
import com.riskwarning.knowledge.dto.RetrievalBackfillResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 独立的检索字段回填服务，不复用或改变旧向量化链。
 * 每个 update 的 doc 同时携带五个检索字段，避免文档处于半更新状态。
 */
@Service
@ConditionalOnExpression("${retrieval.enabled:false} and ${embedding.enabled:false}")
public class RetrievalBackfillService {
    static final String[] SOURCE_FIELDS = {"name", "description", "tags", "fullText",
            "retrievalTextHash", "embeddingModel", "embeddingVersion", "retrievalVector"};
    private static final int SCROLL_SIZE = 200;
    private static final String SCROLL_KEEP_ALIVE = "2m";

    private final ElasticsearchClient client;
    private final AiEmbeddingProvider embedding;
    private final RetrievalTextBuilder textBuilder;
    private final RetrievalProperties retrievalProperties;
    private final EmbeddingProviderProperties embeddingProperties;

    public RetrievalBackfillService(ElasticsearchClient client,
                                    AiEmbeddingProvider embedding,
                                    RetrievalTextBuilder textBuilder,
                                    RetrievalProperties retrievalProperties,
                                    EmbeddingProviderProperties embeddingProperties) {
        this.client = client;
        this.embedding = embedding;
        this.textBuilder = textBuilder;
        this.retrievalProperties = retrievalProperties;
        this.embeddingProperties = embeddingProperties;
        if (embedding.dimension() != 1024 || embeddingProperties.getBatchSize() < 1
                || embeddingProperties.getMaxAttempts() < 1
                || !StringUtils.hasText(retrievalProperties.getEmbeddingVersion())) {
            throw new IllegalArgumentException("检索回填配置不合法");
        }
    }

    /** 执行指定索引的同步回填；绝不创建、删除索引或删除文档。 */
    public RetrievalBackfillResult backfill(RetrievalBackfillRequest request) {
        validateRequest(request);
        long started = System.nanoTime();
        MutableResult result = new MutableResult();
        if (request.getTarget() == RetrievalBackfillRequest.Target.INDICATOR
                || request.getTarget() == RetrievalBackfillRequest.Target.BOTH) {
            scan(request.getIndicatorIndex(), true, request.isForce(), result);
        }
        if (request.getTarget() == RetrievalBackfillRequest.Target.REGULATION
                || request.getTarget() == RetrievalBackfillRequest.Target.BOTH) {
            scan(request.getRegulationIndex(), false, request.isForce(), result);
        }
        return result.freeze((System.nanoTime() - started) / 1_000_000L);
    }

    void validateRequest(RetrievalBackfillRequest request) {
        if (request == null || request.getTarget() == null) {
            throw new IllegalArgumentException("target 必须显式提供");
        }
        boolean needsIndicator = request.getTarget() != RetrievalBackfillRequest.Target.REGULATION;
        boolean needsRegulation = request.getTarget() != RetrievalBackfillRequest.Target.INDICATOR;
        if ((needsIndicator && !StringUtils.hasText(request.getIndicatorIndex()))
                || (needsRegulation && !StringUtils.hasText(request.getRegulationIndex()))) {
            throw new IllegalArgumentException("目标对应的索引必须显式提供");
        }
        String indicator = StringUtils.hasText(request.getIndicatorIndex()) ? request.getIndicatorIndex().trim() : null;
        String regulation = StringUtils.hasText(request.getRegulationIndex()) ? request.getRegulationIndex().trim() : null;
        if (indicator != null && indicator.equals(regulation)) {
            throw new IllegalArgumentException("指标索引与法规索引不得相同");
        }
        request.setIndicatorIndex(indicator);
        request.setRegulationIndex(regulation);
    }

    private void scan(String index, boolean indicator, boolean force, MutableResult result) {
        String scrollId = null;
        try {
            SearchResponse<Map> response = client.search(s -> s.index(index).size(SCROLL_SIZE)
                    .scroll(t -> t.time(SCROLL_KEEP_ALIVE))
                    .source(src -> src.filter(f -> f.includes(Arrays.asList(SOURCE_FIELDS)))), Map.class);
            scrollId = response.scrollId();
            List<Hit<Map>> hits = response.hits().hits();
            while (!hits.isEmpty()) {
                processPage(index, indicator, force, hits, result);
                if (!StringUtils.hasText(scrollId)) {
                    break;
                }
                final String currentScrollId = scrollId;
                ScrollResponse<Map> next = client.scroll(s -> s.scrollId(currentScrollId)
                        .scroll(t -> t.time(SCROLL_KEEP_ALIVE)), Map.class);
                scrollId = next.scrollId();
                hits = next.hits().hits();
            }
        } catch (Exception failure) {
            throw new IllegalStateException("扫描 ES 索引失败: " + index, failure);
        } finally {
            if (StringUtils.hasText(scrollId)) {
                try {
                    final String idToClear = scrollId;
                    client.clearScroll(c -> c.scrollId(idToClear));
                } catch (Exception ignored) {
                    // 清理 scroll 上下文失败不改变已完成文档的回填统计。
                }
            }
        }
    }

    private void processPage(String index, boolean indicator, boolean force,
                             List<Hit<Map>> hits, MutableResult result) {
        List<Plan> pending = new ArrayList<>();
        for (Hit<Map> hit : hits) {
            result.total++;
            try {
                Map source = hit.source();
                if (source == null) {
                    throw new IllegalArgumentException("文档 _source 为空");
                }
                String text = buildText(source, indicator);
                if (!StringUtils.hasText(text)) {
                    throw new IllegalArgumentException("检索文本为空");
                }
                String hash = sha256(text);
                String version = retrievalProperties.getEmbeddingVersion();
                if (!force && shouldSkip(source, hash, embedding.modelId(), version)) {
                    result.skipped++;
                } else {
                    pending.add(new Plan(index, hit.id(), text, hash, embedding.modelId(), version));
                }
            } catch (Exception failure) {
                result.fail(index, hit.id(), reason(failure));
            }
        }
        int batchSize = embeddingProperties.getBatchSize();
        for (int start = 0; start < pending.size(); start += batchSize) {
            List<Plan> batch = pending.subList(start, Math.min(pending.size(), start + batchSize));
            embedAndUpdate(batch, result);
        }
    }

    private void embedAndUpdate(List<Plan> plans, MutableResult result) {
        List<String> texts = new ArrayList<>();
        for (Plan plan : plans) { texts.add(plan.text); }
        List<List<Float>> vectors;
        try {
            vectors = embedding.embed(texts, EmbeddingRole.DOCUMENT);
            if (vectors == null || vectors.size() != plans.size()) {
                throw new IllegalStateException("Embedding 返回数量与请求不一致");
            }
            for (List<Float> vector : vectors) {
                if (vector == null || vector.size() != 1024) {
                    throw new IllegalStateException("Embedding 向量维度不是 1024");
                }
            }
        } catch (Exception failure) {
            for (Plan plan : plans) { result.fail(plan.index, plan.id, reason(failure)); }
            return;
        }
        List<Update> updates = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            updates.add(new Update(plans.get(i), updateFields(plans.get(i), vectors.get(i))));
        }
        bulkWithRetry(updates, result);
    }

    private void bulkWithRetry(List<Update> initial, MutableResult result) {
        List<Update> remaining = new ArrayList<>(initial);
        Map<String, String> lastReasons = new HashMap<>();
        for (int attempt = 1; attempt <= embeddingProperties.getMaxAttempts() && !remaining.isEmpty(); attempt++) {
            List<Update> attempted = new ArrayList<>(remaining);
            try {
                List<BulkOperation> operations = new ArrayList<>();
                for (Update update : attempted) {
                    operations.add(BulkOperation.of(op -> op.update(u -> u.index(update.plan.index)
                            .id(update.plan.id).action(a -> a.doc(update.fields)))));
                }
                // 接口返回时必须可被下一次幂等扫描看见，避免近实时刷新窗口导致重复计算。
                BulkResponse response = client.bulk(new BulkRequest.Builder().operations(operations)
                        .refresh(co.elastic.clients.elasticsearch._types.Refresh.True).build());
                if (response.items().size() != attempted.size()) {
                    throw new IllegalStateException("ES bulk 响应数量与请求不一致");
                }
                remaining.clear();
                for (int i = 0; i < response.items().size(); i++) {
                    BulkResponseItem item = response.items().get(i);
                    Update update = attempted.get(i);
                    if (item.error() == null) {
                        result.updated++;
                    } else {
                        remaining.add(update);
                        lastReasons.put(key(update), item.error().reason());
                    }
                }
            } catch (Exception failure) {
                remaining = attempted;
                for (Update update : remaining) { lastReasons.put(key(update), reason(failure)); }
            }
        }
        for (Update update : remaining) {
            result.fail(update.plan.index, update.plan.id,
                    lastReasons.getOrDefault(key(update), "ES bulk 更新失败"));
        }
    }

    private String buildText(Map source, boolean indicator) {
        if (indicator) {
            return textBuilder.indicatorRetrievalText(Indicator.builder()
                    .name(string(source.get("name"))).description(string(source.get("description")))
                    .tags(strings(source.get("tags"))).build());
        }
        return textBuilder.regulationRetrievalText(Regulation.builder()
                .name(string(source.get("name"))).fullText(string(source.get("fullText"))).build());
    }

    static boolean shouldSkip(Map source, String hash, String model, String version) {
        Object vector = source.get("retrievalVector");
        return Objects.equals(hash, string(source.get("retrievalTextHash")))
                && Objects.equals(model, string(source.get("embeddingModel")))
                && Objects.equals(version, string(source.get("embeddingVersion")))
                && vector instanceof Collection && !((Collection) vector).isEmpty();
    }

    static Map<String, Object> updateFields(Plan plan, List<Float> vector) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("retrievalText", plan.text);
        fields.put("retrievalVector", vector);
        fields.put("retrievalTextHash", plan.hash);
        fields.put("embeddingModel", plan.model);
        fields.put("embeddingVersion", plan.version);
        return fields;
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) { output.append(String.format(Locale.ROOT, "%02x", value & 0xff)); }
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
    }

    private static String key(Update update) { return update.plan.index + "\n" + update.plan.id; }
    private static String reason(Throwable failure) {
        return StringUtils.hasText(failure.getMessage()) ? failure.getMessage() : failure.getClass().getSimpleName();
    }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
    private static List<String> strings(Object value) {
        if (!(value instanceof Collection)) { return Collections.emptyList(); }
        List<String> output = new ArrayList<>();
        for (Object item : (Collection) value) { if (item != null) { output.add(String.valueOf(item)); } }
        return output;
    }

    static final class Plan {
        final String index;
        final String id;
        final String text;
        final String hash;
        final String model;
        final String version;

        Plan(String index, String id, String text, String hash, String model, String version) {
            this.index = index;
            this.id = id;
            this.text = text;
            this.hash = hash;
            this.model = model;
            this.version = version;
        }
    }

    private static final class Update {
        final Plan plan;
        final Map<String, Object> fields;
        Update(Plan plan, Map<String, Object> fields) { this.plan = plan; this.fields = fields; }
    }

    private static final class MutableResult {
        long total;
        long updated;
        long skipped;
        final List<RetrievalBackfillResult.Failure> failures = new ArrayList<>();
        void fail(String index, String id, String reason) {
            failures.add(RetrievalBackfillResult.Failure.builder().index(index).id(id).reason(reason).build());
        }
        RetrievalBackfillResult freeze(long elapsed) {
            return RetrievalBackfillResult.builder().total(total).updated(updated).skipped(skipped)
                    .failed(failures.size()).failures(new ArrayList<>(failures)).elapsedMillis(elapsed).build();
        }
    }
}
