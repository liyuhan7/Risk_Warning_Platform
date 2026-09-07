package com.riskwarning.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 大模型 Provider 配置边界。
 *
 * 契约按 OpenAI 兼容的 /chat/completions 形状定义，因此更换供应商（千帆、DeepSeek、
 * 百炼兼容模式等）只需调整 baseUrl、model 与凭据，无需修改 Java 代码。
 *
 * 凭据只能来自环境变量或本地配置，禁止写入仓库。apiKey 缺失时 Provider 拒绝启动，
 * 避免带着空凭据发出必然失败的请求。
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
@Data
public class LlmProviderProperties {

    /**
     * 是否启用大模型调用。默认关闭：common 被五个模块整包扫描，
     * 不启用时不创建 Provider Bean，避免与本次调用无关的服务多出依赖。
     */
    private Boolean enabled = false;

    /** 兼容 OpenAI 协议的完整 Chat Completions 端点 */
    private String baseUrl;

    /** 模型标识，由供应商定义 */
    private String model;

    /** 凭据，通过 ${LLM_API_KEY} 注入 */
    private String apiKey;

    /** 采样温度。固定 Prompt 的结构化输出要求确定性，默认 0。
     * 供应商默认值通常大于 0，会使同一 Prompt 多次调用结果不稳定。
     */
    private Double temperature = 0.0D;

    /**
     * 附加请求体字段（JSON 字符串，如 {"thinking":{"type":"disabled"}}）。
     * 供需要携带供应商可选参数的场景使用，顶层按键与固定字段合并；
     * 合并发生在固定字段（model/messages/temperature）写入之前，固定字段优先级更高。
     */
    private String extraBody;

    private Integer connectTimeoutSeconds = 30;

    private Integer readTimeoutSeconds = 60;

    private Integer writeTimeoutSeconds = 30;

    private Retry retry = new Retry();

    /**
     * 重试策略。Provider 侧的瞬时故障（限流、网关错误、读超时）不应直接冒泡为业务失败，
     * 但语义错误（参数非法、鉴权失败、余额不足）重试无意义，必须立即失败。
     */
    @Data
    public static class Retry {

        /** 总尝试次数，含首次请求。设为 1 表示不重试 */
        private Integer maxAttempts = 3;

        private Long initialBackoffMillis = 500L;

        private Double backoffMultiplier = 2.0D;

        /** 判定为可重试的 HTTP 状态码；其余状态码一次失败即终止 */
        private List<Integer> retryableStatusCodes = Arrays.asList(408, 429, 500, 502, 503, 504);
    }
}
