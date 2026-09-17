package com.riskwarning.knowledge.controller;

import com.riskwarning.common.dto.retrieval.RetrievalBatchRequest;
import com.riskwarning.common.dto.retrieval.RetrievalBatchResponse;
import com.riskwarning.knowledge.service.RetrievalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 新检索链的微服务边界；业务错误不返回部分成功。 */
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
        try {
            return retrievalService.retrieveBatch(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
