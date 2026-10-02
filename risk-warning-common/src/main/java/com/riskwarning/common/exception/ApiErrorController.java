package com.riskwarning.common.exception;

import com.riskwarning.common.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;

/** Servlet 容器错误分派也遵循统一 envelope，包括未命中控制器的路由。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiErrorController implements ErrorController {
    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<Result<?>> error(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = attribute instanceof Number ? ((Number) attribute).intValue() : 500;
        return ErrorResponses.error(code, code == 404 ? "请求的资源不存在" : "请求处理失败");
    }
}
