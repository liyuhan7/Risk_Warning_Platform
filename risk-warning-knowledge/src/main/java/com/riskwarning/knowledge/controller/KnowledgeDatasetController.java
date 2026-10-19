package com.riskwarning.knowledge.controller;

import com.riskwarning.common.dto.scoring.ScoringSourceBundle;
import com.riskwarning.knowledge.service.KnowledgeDatasetService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 发布可校验版本的评分依据；历史版本不回退到当前数据。 */
@RestController
@RequestMapping("/scoring/datasets")
@ConditionalOnProperty(prefix = "knowledge.dataset", name = "enabled", havingValue = "true")
public class KnowledgeDatasetController {
    private final KnowledgeDatasetService datasets;

    public KnowledgeDatasetController(KnowledgeDatasetService datasets) { this.datasets = datasets; }

    @GetMapping("/current")
    public Map<String, Object> current() { return datasets.manifest(); }

    @GetMapping("/{version}/indicators/{indicatorId}")
    public ScoringSourceBundle bundle(@PathVariable String version, @PathVariable String indicatorId) {
        return datasets.bundle(version, indicatorId);
    }
}
