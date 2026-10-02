package com.riskwarning.knowledge.controller;

import com.riskwarning.common.dto.retrieval.RetrievalBatchRequest;
import com.riskwarning.common.dto.retrieval.RetrievalBatchResponse;
import com.riskwarning.knowledge.service.RetrievalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 检索链的微服务边界；整批失败时保留异常，由共用处理器决定错误响应。 */
@RestController
@RequestMapping("/retrieval")
@ConditionalOnProperty(prefix = "retrieval", name = "enabled", havingValue = "true")
public class RetrievalController {
    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/batch")
    public RetrievalBatchResponse batch(@RequestBody RetrievalBatchRequest request) {
        return retrievalService.retrieveBatch(request);
    }
}
