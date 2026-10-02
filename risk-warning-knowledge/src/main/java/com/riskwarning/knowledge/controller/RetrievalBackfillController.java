package com.riskwarning.knowledge.controller;

import com.riskwarning.knowledge.dto.RetrievalBackfillRequest;
import com.riskwarning.knowledge.dto.RetrievalBackfillResult;
import com.riskwarning.knowledge.service.RetrievalBackfillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import com.riskwarning.common.result.Result;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<?> backfill(@RequestBody RetrievalBackfillRequest request) {
        RetrievalBackfillResult result = service.backfill(request);
        if (result == null) throw new IllegalStateException("回填结果为空");
        if (result.getFailed() > 0) {
            // 保留已完成统计及失败文档身份，供应商与存储异常正文只留在服务侧。
            List<RetrievalBackfillResult.Failure> failures = new ArrayList<>();
            if (result.getFailures() != null) {
                for (RetrievalBackfillResult.Failure failure : result.getFailures()) {
                    if (failure != null) failures.add(RetrievalBackfillResult.Failure.builder()
                            .index(failure.getIndex()).id(failure.getId()).reason("回填失败").build());
                }
            }
            RetrievalBackfillResult details = RetrievalBackfillResult.builder().total(result.getTotal())
                    .updated(result.getUpdated()).skipped(result.getSkipped()).failed(result.getFailed())
                    .elapsedMillis(result.getElapsedMillis()).failures(failures).build();
            return ResponseEntity.status(500).body(new Result<>(500, "检索字段回填未全部完成", details));
        }
        return ResponseEntity.ok(result);
    }
}
