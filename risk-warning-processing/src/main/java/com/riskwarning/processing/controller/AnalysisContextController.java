package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.dto.analysis.AnalysisContextVO;
import com.riskwarning.processing.service.AnalysisContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/** 后续分析上下文接口，只读返回已持久化的事实、证据引用与检索候选。 */
@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
@Slf4j
public class AnalysisContextController {
    private final AnalysisContextService service;

    @AuthRequired
    @GetMapping("/context/{assessmentId}")
    public Result<AnalysisContextVO> context(@PathVariable Long assessmentId,
                                             @RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) String analysisRunId) {
        try { return Result.success(service.context(assessmentId, projectId, analysisRunId)); }
        catch (IllegalArgumentException e) { return Result.fail(400, e.getMessage()); }
        catch (SecurityException e) { return Result.fail(403, e.getMessage()); }
        catch (NoSuchElementException e) { return Result.fail(404, e.getMessage()); }
        catch (Exception e) {
            log.error("分析上下文聚合失败: assessmentId={}, projectId={}", assessmentId, projectId, e);
            return Result.fail(500, "分析上下文聚合失败");
        }
    }
}
