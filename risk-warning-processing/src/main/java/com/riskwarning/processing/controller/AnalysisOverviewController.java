package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.dto.analysis.AnalysisOverviewVO;
import com.riskwarning.processing.service.AnalysisOverviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;

/** 分析概览接口，错误码沿用统一响应体契约。 */
@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
@Slf4j
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
        try { return Result.success(service.overview(assessmentId, projectId, analysisRunId)); }
        catch (IllegalArgumentException e) { return Result.fail(400, e.getMessage()); }
        catch (SecurityException e) { return Result.fail(403, e.getMessage()); }
        catch (NoSuchElementException e) { return Result.fail(404, e.getMessage()); }
        catch (Exception e) {
            log.error("分析概览聚合失败: assessmentId={}, projectId={}", assessmentId, projectId, e);
            return Result.fail(500, "分析概览聚合失败");
        }
    }
}
