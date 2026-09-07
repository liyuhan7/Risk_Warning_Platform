package com.riskwarning.common.provider;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.riskwarning.common.config.LlmProviderProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 面向 OpenAI 兼容 /chat/completions 协议的 Provider 实现。
 *
 * 千帆 v2、DeepSeek、月之暗面、百炼兼容模式等共用同一请求与响应形状，
 * 因此更换供应商只调整配置。仅在 llm.enabled=true 时注册，
 * 使未使用大模型的服务不加载本 Bean。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "llm", name = "enabled", havingValue = "true")
public class OpenAiCompatibleChatProvider implements AiChatProvider {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final LlmProviderProperties properties;

    private final OkHttpClient client;

    /** 附加请求体字段的解析结果；未配置时为 null。 */
    private final JSONObject extraBodyJson;

    public OpenAiCompatibleChatProvider(LlmProviderProperties properties) {
        // 启用后凭据与端点必须齐备，否则每次调用都会失败，及早暴露配置缺失优于运行期报错
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("llm.enabled=true 但未提供 llm.api-key，请通过环境变量注入");
        }
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new IllegalStateException("llm.enabled=true 但未配置 llm.base-url");
        }
        if (!StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("llm.enabled=true 但未配置 llm.model");
        }
        this.properties = properties;
        this.extraBodyJson = parseExtraBody(properties.getExtraBody());
        this.client = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(properties.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(properties.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
        log.info("大模型 Provider 已启用，endpoint={}，model={}，temperature={}",
                properties.getBaseUrl(), properties.getModel(), properties.getTemperature());
    }

    @Override
    public String modelId() {
        return properties.getModel();
    }

    @Override
    public String chat(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("prompt 不得为空");
        }

        int maxAttempts = Math.max(1, properties.getRetry().getMaxAttempts());
        long backoffMillis = properties.getRetry().getInitialBackoffMillis();
        LlmProviderException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeOnce(prompt);
            } catch (LlmProviderException e) {
                lastFailure = e;
                // 不可重试的失败立即上抛，避免对语义错误或账户问题做无效重试
                if (!e.isRetryable() || attempt == maxAttempts) {
                    throw e;
                }
                log.warn("大模型调用第 {}/{} 次失败，status={}，{}ms 后重试：{}",
                        attempt, maxAttempts, e.getHttpStatus(), backoffMillis, e.getMessage());
                sleep(backoffMillis);
                backoffMillis = (long) (backoffMillis * properties.getRetry().getBackoffMultiplier());
            }
        }

        throw lastFailure;
    }

    /**
     * 解析附加请求体 JSON。配置非法 JSON 属配置错误，在构造阶段及早暴露。
     *
     * @return 解析后的顶层对象；未配置时为 null
     */
    private JSONObject parseExtraBody(String extraBody) {
        if (!StringUtils.hasText(extraBody)) {
            return null;
        }
        try {
            JSONObject parsed = JSONUtil.parseObj(extraBody);
            if (!parsed.isEmpty() && parsed.containsKey("apiKey")) {
                throw new IllegalStateException("llm.extra-body 禁止携带 apiKey");
            }
            return parsed;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "llm.extra-body 不是合法 JSON 对象：" + e.getMessage(), e);
        }
    }

    /**
     * 发起单次请求并解析正文。
     *
     * @throws LlmProviderException 网络异常、非 2xx 响应或响应结构不符合预期
     */
    private String executeOnce(String prompt) {
        JSONObject requestBody = new JSONObject();
        // 附加字段先写入，固定字段后写入以保持固定契约优先级最高
        if (extraBodyJson != null) {
            extraBodyJson.forEach(requestBody::set);
        }
        JSONObject message = new JSONObject();
        message.set("role", "user");
        message.set("content", prompt);

        JSONArray messages = new JSONArray();
        messages.add(message);

        requestBody.set("model", properties.getModel());
        requestBody.set("messages", messages);
        // 结构化输出依赖确定性，必须显式指定温度而非沿用供应商默认值
        requestBody.set("temperature", properties.getTemperature());

        Request request = new Request.Builder()
                .url(properties.getBaseUrl())
                .post(RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + properties.getApiKey())
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = readBody(response);

            if (!response.isSuccessful()) {
                boolean retryable = properties.getRetry()
                        .getRetryableStatusCodes()
                        .contains(response.code());
                throw new LlmProviderException(
                        "大模型返回非成功状态 " + response.code() + "：" + truncate(responseBody),
                        response.code(), retryable);
            }

            return extractContent(responseBody);
        } catch (IOException e) {
            // 连接失败与读超时属于瞬时故障，交由重试处理
            throw new LlmProviderException("大模型调用发生网络异常：" + e.getMessage(), -1, true, e);
        }
    }

    private String readBody(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    /**
     * 从兼容协议响应中取出首个候选的正文。
     *
     * 结构缺失视为不可重试：同样的请求重发只会得到同样的结构。
     */
    private String extractContent(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            throw new LlmProviderException("大模型返回空响应体", 200, false);
        }

        JSONObject responseJson;
        try {
            responseJson = JSONUtil.parseObj(responseBody);
        } catch (Exception e) {
            throw new LlmProviderException("大模型响应不是合法 JSON：" + truncate(responseBody), 200, false, e);
        }

        JSONArray choices = responseJson.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmProviderException("大模型响应缺少 choices：" + truncate(responseBody), 200, false);
        }

        JSONObject firstChoice = choices.getJSONObject(0);
        JSONObject message = firstChoice == null ? null : firstChoice.getJSONObject("message");
        String content = message == null ? null : message.getStr("content");
        if (!StringUtils.hasText(content)) {
            throw new LlmProviderException("大模型响应缺少 message.content：" + truncate(responseBody), 200, false);
        }
        return content;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException("大模型调用重试等待被中断", -1, false, e);
        }
    }

    /**
     * 截断响应正文用于日志与异常消息，避免超长内容淹没日志。
     */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...(truncated)";
    }
}
