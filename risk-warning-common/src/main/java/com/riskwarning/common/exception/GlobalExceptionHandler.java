package com.riskwarning.common.exception;

import com.riskwarning.common.result.Result;
import com.riskwarning.common.provider.EmbeddingProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import javax.validation.ConstraintViolationException;
import java.util.NoSuchElementException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ErrorResponses.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理系统异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleException(Exception e) {
        log.error("系统异常: ", e);
        return ErrorResponses.error(500, "系统异常，请稍后重试");
    }

    /**
     * 缺少参数异常
     */
    @ExceptionHandler({IllegalArgumentException.class, BindException.class, ConstraintViolationException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<?>> handleIllegalArgumentException(Exception e) {
        log.warn("参数异常: {}", e.getMessage());
        return ErrorResponses.error(400, "参数异常: " + e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String errorMsg = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .reduce((msg1, msg2) -> msg1 + "; " + msg2)
                .orElse("参数校验失败");
        log.warn("参数校验异常: {}", errorMsg);
        return ErrorResponses.error(400, "参数校验异常: " + errorMsg);
    }

    /**
     * 缺少RequestBody异常
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体缺失或格式错误: {}", e.getMessage());
        return ErrorResponses.error(400, "请求体缺失或格式错误");
    }

    /**
     * 无路径匹配异常
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<?>> handleNoHandlerFoundException(NoHandlerFoundException e) {
        log.warn("无效的请求路径: {}", e.getRequestURL());
        return ErrorResponses.error(404, "无效的请求路径: " + e.getRequestURL());
    }

    @ExceptionHandler(EmbeddingProviderException.class)
    public ResponseEntity<Result<?>> handleEmbeddingProvider(EmbeddingProviderException failure) {
        log.warn("Embedding 调用失败，供应商状态 {}，可重试 {}", failure.getHttpStatus(), failure.isRetryable());
        return ErrorResponses.error(failure.isRetryable() ? 503 : 502,
                failure.isRetryable() ? "向量服务暂时不可用" : "向量服务响应不可接受");
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<?>> handleForbidden(SecurityException exception) {
        return ErrorResponses.error(403, exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Result<?>> handleNotFound(NoSuchElementException exception) {
        return ErrorResponses.error(404, exception.getMessage());
    }

    @ExceptionHandler(AnalysisInputConflictException.class)
    public ResponseEntity<Result<?>> handleInputConflict(AnalysisInputConflictException exception) {
        return ErrorResponses.error(409, exception.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<?>> handleResponseStatus(ResponseStatusException exception) {
        return ErrorResponses.error(exception.getStatus().value(), exception.getReason());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return ErrorResponses.error(405, "请求方法不支持");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<?>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ErrorResponses.error(415, "请求内容类型不支持");
    }
}
