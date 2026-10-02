package com.riskwarning.common.exception;

import com.riskwarning.common.result.Result;
import org.springframework.http.*;

/** 错误状态与响应体使用同一值；无效业务码按未分类系统错误处理。 */
public final class ErrorResponses {
    private ErrorResponses() { }

    public static ResponseEntity<Result<?>> error(Integer code, String message) {
        HttpStatus status = code == null ? null : HttpStatus.resolve(code);
        if (status == null || !status.isError()) status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(status.value(), message == null ? status.getReasonPhrase() : message));
    }
}
