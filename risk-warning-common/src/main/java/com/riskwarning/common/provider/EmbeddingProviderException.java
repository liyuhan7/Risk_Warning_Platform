package com.riskwarning.common.provider;

import lombok.Getter;

/** 保留供应商失败类别，禁止以零向量替代失败。 */
@Getter
public class EmbeddingProviderException extends RuntimeException {
    private final int httpStatus;
    private final boolean retryable;

    public EmbeddingProviderException(String message, int httpStatus, boolean retryable) {
        this(message, httpStatus, retryable, null);
    }

    public EmbeddingProviderException(String message, int httpStatus, boolean retryable, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }
}
