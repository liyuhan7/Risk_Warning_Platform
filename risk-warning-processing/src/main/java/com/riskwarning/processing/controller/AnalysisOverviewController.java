package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.dto.analysis.AnalysisOverviewVO;
import com.riskwarning.processing.service.AnalysisOverviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 分析概览接口；异常由共用处理器转换为真实 HTTP 错误响应。 */
@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisOverviewController {
    private final AnalysisOverviewService service;

    public Result<AnalysisOverviewVO> overview(Long assessmentId, Long projectId) {
        return overview(assessmentId, projectId, null);
    }

    @AuthRequired
    @GetMapping("/overview/{assessmentId}")
    public Result<AnalysisOverviewVO> overview(@PathVariable Long assessmentId,
                                              @RequestParam(required = false) Long projectId,
                                              @RequestParam(required = false) String analysisRunId) {
        return Result.success(service.overview(assessmentId, projectId, analysisRunId));
    }
}
