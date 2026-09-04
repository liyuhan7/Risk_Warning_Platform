package com.riskwarning.common.provider;

/**
 * 大模型调用失败。
 *
 * 区分可重试与不可重试，使上层能够判断失败性质：可重试代表 Provider 瞬时不可用，
 * 不可重试代表请求本身或账户状态有问题，重试只会放大故障。
 * 保留 httpStatus 与响应正文，便于排查供应商侧的具体错误码。
 */
public class LlmProviderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 供应商返回的 HTTP 状态码；非 HTTP 层失败（如连接中断）为 -1 */
    private final int httpStatus;

    private final boolean retryable;

    public LlmProviderException(String message, int httpStatus, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public LlmProviderException(String message, int httpStatus, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
