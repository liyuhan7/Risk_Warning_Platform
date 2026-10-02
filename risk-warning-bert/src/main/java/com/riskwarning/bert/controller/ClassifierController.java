package com.riskwarning.bert.controller;

import com.riskwarning.bert.service.PythonServiceManager;
import com.riskwarning.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** 分类代理保留成功协议，并区分请求错误与下游服务故障。 */
@RestController
@RequestMapping("/classify")
@RequiredArgsConstructor
public class ClassifierController {
    private final PythonServiceManager pythonServiceManager;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return invoke(pythonServiceManager::checkClassifierHealth, false);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> classify(@RequestBody Map<String, Object> request) {
        if (!validItem(request)) {
            throw new IllegalArgumentException("文本不能为空，input_type 必须为 behavior、indicator 或 regulation");
        }
        return invoke(() -> pythonServiceManager.classify(request), true);
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> classifyBatch(@RequestBody Map<String, Object> request) {
        Object items = request.get("items");
        if (!(items instanceof List) || ((List<?>) items).isEmpty()) {
            throw new IllegalArgumentException("items 必须是非空数组");
        }
        boolean hasValidItem = false;
        for (Object item : (List<?>) items) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("items 条目必须为对象");
            }
            hasValidItem |= validItem((Map<?, ?>) item);
        }
        if (!hasValidItem) {
            throw new IllegalArgumentException("没有有效的文本");
        }
        return invoke(() -> pythonServiceManager.classifyBatch(request), true);
    }

    private boolean validItem(Map<?, ?> item) {
        Object text = item.get("text");
        Object type = item.containsKey("input_type") ? item.get("input_type") : "behavior";
        if ((text != null && !(text instanceof String)) || !(type instanceof String)) {
            throw new IllegalArgumentException("text 和 input_type 必须为字符串");
        }
        String normalized = ((String) type).trim().toLowerCase(Locale.ROOT);
        return text != null && !((String) text).trim().isEmpty()
                && ("behavior".equals(normalized) || "indicator".equals(normalized) || "regulation".equals(normalized));
    }

    /** 下游认证失败属于代理故障，不能解释为当前用户认证失败。 */
    private ResponseEntity<Map<String, Object>> invoke(Supplier<Map<String, Object>> action, boolean userRequest) {
        if (!pythonServiceManager.isServiceRunning()) {
            throw new BusinessException(503, "分类服务暂时不可用");
        }
        try {
            Map<String, Object> result = action.get();
            if (result == null) {
                throw new BusinessException(502, "分类服务响应不可接受");
            }
            return ResponseEntity.ok(result);
        } catch (ResourceAccessException e) {
            throw new BusinessException(503, "分类服务暂时不可用");
        } catch (RestClientResponseException e) {
            int status = e.getRawStatusCode();
            if (userRequest && (status == 400 || status == 422)) {
                throw new BusinessException(400, "分类请求不符合服务要求");
            }
            if (status == 408 || status == 429 || status >= 500) {
                throw new BusinessException(503, "分类服务暂时不可用");
            }
            throw new BusinessException(502, "分类服务响应不可接受");
        } catch (RestClientException e) {
            throw new BusinessException(502, "分类服务响应不可接受");
        }
    }
}
