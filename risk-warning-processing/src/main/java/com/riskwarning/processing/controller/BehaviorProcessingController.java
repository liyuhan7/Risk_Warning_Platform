package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.context.UserContext;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.service.BehaviorProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 行为处理控制器
 *
 * 提供一个简单的入口，用于触发 BehaviorProcessingService 的计算并持久化到数据库。
 */
@Slf4j
@RestController
@RequestMapping("/behavior")
@RequiredArgsConstructor
public class BehaviorProcessingController {

    private final BehaviorProcessingService behaviorProcessingService;

    /**
     * 新接口：根据 projectId 处理该项目的所有 behaviors
     *
     * 1. 从 ES 获取该项目的所有 behaviors
     * 2. 创建一个 assessment 记录
     * 3. 依次处理每个 behavior 并计算指标
     * 4. 将结果保存到数据库
     *
     * @param projectId 项目ID
     * @return 聚合的评估结果
     */
    @PostMapping("/process-project/{projectId}/{assessmentId}/{analysisRunId}")
    @AuthRequired
    public Result processProject(@PathVariable Long projectId, @PathVariable Long assessmentId,
                                 @PathVariable String analysisRunId) {
        try {
            log.info("开始处理项目的所有行为: projectId={}", projectId);
            behaviorProcessingService.processProjectBehaviors(
                    UserContext.getUser().getId(), projectId, assessmentId, analysisRunId);
            return Result.success("项目行为处理开始");
        } catch (IllegalArgumentException e) {
            log.warn("处理项目行为时参数错误: projectId={}, error={}", projectId, e.getMessage());
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("处理项目行为失败: projectId={}", projectId, e);
            return Result.fail("处理项目行为失败: " + e.getMessage());
        }
    }
}

