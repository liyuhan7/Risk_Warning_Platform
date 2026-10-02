package com.riskwarning.gateway.exception;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/** 网关框架和上游连接错误统一返回业务响应，已提交响应交由服务器终止。 */
@Component
@Order(-2)
@Slf4j
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "服务内部错误";
        if (failure instanceof ResponseStatusException) {
            status = errorStatus(((ResponseStatusException) failure).getRawStatusCode());
            message = status.getReasonPhrase();
        } else if (failure instanceof BusinessException) {
            BusinessException business = (BusinessException) failure;
            status = errorStatus(business.getCode());
            if (status.is4xxClientError() && business.getMessage() != null) {
                message = business.getMessage();
            } else {
                message = status.getReasonPhrase();
            }
        } else if (connectionUnavailable(failure)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            message = "上游服务暂时不可用";
        }
        if (status.is5xxServerError()) {
            log.error("网关请求失败，HTTP 状态 {}", status.value(), failure);
        }
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = JSON.toJSONString(Result.fail(status.value(), message), JSONWriter.Feature.WriteNulls)
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private HttpStatus errorStatus(Integer code) {
        HttpStatus status = code == null ? null : HttpStatus.resolve(code);
        return status != null && status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private boolean connectionUnavailable(Throwable failure) {
        // 外部客户端可能包装连接故障；身份集合防止异常 cause 环造成无限循环。
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
