package com.riskwarning.knowledge.controller;

import com.riskwarning.knowledge.dto.RetrievalBackfillRequest;
import com.riskwarning.knowledge.dto.RetrievalBackfillResult;
import com.riskwarning.knowledge.service.RetrievalBackfillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 受控的检索字段回填入口。 */
@RestController
@RequestMapping("/retrieval/backfill")
@ConditionalOnExpression("${retrieval.enabled:false} and ${embedding.enabled:false}")
public class RetrievalBackfillController {
    private final RetrievalBackfillService service;

    public RetrievalBackfillController(RetrievalBackfillService service) {
        this.service = service;
    }

    @PostMapping
    public RetrievalBackfillResult backfill(@RequestBody RetrievalBackfillRequest request) {
        try {
            return service.backfill(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }
}
