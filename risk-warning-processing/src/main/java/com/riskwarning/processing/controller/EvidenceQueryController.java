package com.riskwarning.processing.controller;

import com.riskwarning.common.annotation.AuthRequired;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.result.Result;
import com.riskwarning.processing.dto.EvidenceVO;
import com.riskwarning.processing.service.EvidenceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 证据回溯只读接口。
 *
 * 供评估结果页展示“行为引用了哪些原文”，查询一律需要项目与评估身份；
 * 参数不合法返回 400，归属不符返回 403，完整性失败返回 500，不返回可疑数据。
 */
@RestController
@RequestMapping("/evidence")
@RequiredArgsConstructor
public class EvidenceQueryController {

    private final EvidenceQueryService evidenceQueryService;

    @GetMapping
    @AuthRequired
    public Result<List<EvidenceVO>> list(@RequestParam Long projectId,
                                         @RequestParam Long assessmentId,
                                         @RequestParam(required = false) Long sourceDocumentId) {
        return Result.success(toVOs(evidenceQueryService.listByScope(projectId, assessmentId, sourceDocumentId)));
    }

    @GetMapping("/by-ids")
    @AuthRequired
    public Result<List<EvidenceVO>> byIds(@RequestParam String ids,
                                          @RequestParam Long projectId,
                                          @RequestParam Long assessmentId) {
        return Result.success(toVOs(evidenceQueryService.listByIds(parseIds(ids), projectId, assessmentId)));
    }

    private List<String> parseIds(String ids) {
        if (ids == null || ids.trim().isEmpty()) {
            throw new IllegalArgumentException("ids 不能为空");
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toList());
    }

    private List<EvidenceVO> toVOs(List<EvidenceChunk> chunks) {
        return chunks.stream().map(EvidenceVO::from).collect(Collectors.toList());
    }
}
