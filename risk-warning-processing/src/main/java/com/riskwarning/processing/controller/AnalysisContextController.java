package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.dto.analysis.AnalysisContextVO;
import com.riskwarning.processing.service.AnalysisContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


/** 后续分析上下文接口，只读返回已持久化的事实、证据引用与检索候选。 */
@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisContextController {
    private final AnalysisContextService service;

    @AuthRequired
    @GetMapping("/context/{assessmentId}")
    public Result<AnalysisContextVO> context(@PathVariable Long assessmentId,
                                             @RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) String analysisRunId) {
        return Result.success(service.context(assessmentId, projectId, analysisRunId));
    }
}
