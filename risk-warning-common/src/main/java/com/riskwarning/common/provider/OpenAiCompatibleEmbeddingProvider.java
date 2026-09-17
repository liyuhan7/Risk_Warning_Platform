package com.riskwarning.common.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.config.EmbeddingProviderProperties;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** OpenAI 兼容 Embeddings 适配器，响应结构错误不重试，网络瞬时故障有限重试。 */
@Component
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "true")
public class OpenAiCompatibleEmbeddingProvider implements AiEmbeddingProvider {
    private final EmbeddingProviderProperties properties;
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleEmbeddingProvider(EmbeddingProviderProperties properties) {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getEndpoint())
                || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("Embedding 端点、模型和 EMBEDDING_API_KEY 必须配置");
        }
        if (properties.getDimension() != 1024 || properties.getBatchSize() < 1
                || properties.getBatchSize() > 16 || properties.getTimeoutSeconds() < 1
                || properties.getMaxAttempts() < 1 || properties.getMaxAttempts() > 3
                || properties.getInitialBackoffMillis() < 0) {
            throw new IllegalArgumentException("Embedding 配置不符合冻结参数");
        }
        this.properties = properties;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(properties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(properties.getTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(properties.getTimeoutSeconds(), TimeUnit.SECONDS).build();
    }

    @Override public String modelId() { return properties.getModel(); }
    @Override public int dimension() { return properties.getDimension(); }

    @Override
    public List<List<Float>> embed(List<String> texts, EmbeddingRole role) {
        if (texts == null || texts.isEmpty() || role == null
                || texts.stream().anyMatch(text -> !StringUtils.hasText(text))) {
            throw new IllegalArgumentException("Embedding 输入文本及角色不得为空");
        }
        List<String> input = new ArrayList<>(texts);
        List<List<Float>> output = new ArrayList<>();
        for (int start = 0; start < input.size(); start += properties.getBatchSize()) {
            List<String> batch = input.subList(start, Math.min(input.size(), start + properties.getBatchSize()));
            output.addAll(embedBatch(batch));
        }
        return output;
    }

    private List<List<Float>> embedBatch(List<String> batch) {
        long backoff = properties.getInitialBackoffMillis();
        for (int attempt = 1; ; attempt++) {
            try {
                return execute(batch);
            } catch (EmbeddingProviderException failure) {
                if (!failure.isRetryable() || attempt >= properties.getMaxAttempts()) { throw failure; }
                try { Thread.sleep(backoff); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingProviderException("Embedding 重试被中断", -1, false, interrupted);
                }
                backoff = backoff > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : backoff * 2;
            }
        }
    }

    private List<List<Float>> execute(List<String> batch) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelId());
            body.put("input", batch);
            body.put("encoding_format", "float");
            Request request = new Request.Builder().url(properties.getEndpoint())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .post(RequestBody.create(mapper.writeValueAsString(body), MediaType.parse("application/json")))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                int status = response.code();
                if (!response.isSuccessful()) {
                    // 不回显供应商正文，避免异常日志泄露材料或账户信息。
                    throw new EmbeddingProviderException("Embedding HTTP 调用失败", status,
                            status == 408 || status == 429 || status >= 500);
                }
                return decode(response.body() == null ? "" : response.body().string(), batch.size());
            }
        } catch (IOException failure) {
            throw new EmbeddingProviderException("Embedding 网络异常", -1, true, failure);
        }
    }

    private List<List<Float>> decode(String body, int count) {
        try {
            JsonNode data = mapper.readTree(body).path("data");
            if (!data.isArray() || data.size() != count) { throw new IllegalArgumentException("响应数量不符"); }
            List<List<Float>> result = new ArrayList<>(Collections.nCopies(count, null));
            for (JsonNode item : data) {
                JsonNode indexNode = item.path("index");
                if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) {
                    throw new IllegalArgumentException("index 非法");
                }
                int index = indexNode.intValue();
                if (index < 0 || index >= count || result.get(index) != null) {
                    throw new IllegalArgumentException("index 越界或重复");
                }
                JsonNode vector = item.path("embedding");
                if (!vector.isArray() || vector.size() != dimension()) {
                    throw new IllegalArgumentException("向量维度不符");
                }
                double norm = 0;
                for (JsonNode value : vector) {
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                        throw new IllegalArgumentException("向量含非有限数值");
                    }
                    norm = Math.hypot(norm, value.doubleValue());
                }
                if (!Double.isFinite(norm) || norm == 0) { throw new IllegalArgumentException("向量范数非法"); }
                List<Float> normalized = new ArrayList<>();
                for (JsonNode value : vector) { normalized.add((float) (value.doubleValue() / norm)); }
                result.set(index, normalized);
            }
            return result;
        } catch (Exception failure) {
            throw new EmbeddingProviderException("Embedding 响应校验失败", 200, false, failure);
        }
    }
}
