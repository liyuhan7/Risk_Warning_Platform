package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.dto.BehaviorListVO;
import com.riskwarning.processing.service.BehaviorQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构化事实只读接口。
 *
 * 供评估结果页"证据回溯"展示行为与证据的追溯链。
 * 注意与 {@link BehaviorProcessingController} 共用 /behavior 前缀：
 * 触发接口是 POST /behavior/process-project/...，本接口是 GET /behavior，方法路径不冲突。
 */
@Slf4j
@RestController
@RequestMapping("/behavior")
@RequiredArgsConstructor
public class BehaviorQueryController {

    private final BehaviorQueryService behaviorQueryService;

    @GetMapping
    @AuthRequired
    public Result<BehaviorListVO> list(@RequestParam Long projectId,
                                       @RequestParam Long assessmentId,
                                       @RequestParam(required = false) String analysisRunId) {
        try {
            return Result.success(
                    behaviorQueryService.listByScope(projectId, assessmentId, analysisRunId));
        } catch (IllegalArgumentException e) {
            log.warn("[Behavior Query] 参数错误: projectId={}, assessmentId={}, error={}",
                    projectId, assessmentId, e.getMessage());
            return Result.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("[Behavior Query] 查询失败: projectId={}, assessmentId={}, error={}",
                    projectId, assessmentId, e.getMessage());
            return Result.fail(500, e.getMessage());
        }
    }
}