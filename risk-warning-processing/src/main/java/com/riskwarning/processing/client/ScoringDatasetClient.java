package com.riskwarning.processing.client;

import com.riskwarning.common.dto.scoring.ScoringSourceBundle;
import com.riskwarning.processing.config.RetrievalFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 版本化评分依据的只读服务边界。 */
@FeignClient(name = "scoring-dataset-service", url = "${knowledge.service.url:http://localhost:8089}",
        path = "/scoring/datasets", configuration = RetrievalFeignConfiguration.class)
public interface ScoringDatasetClient {
    @GetMapping("/current")
    Map<String, Object> current();

    @GetMapping("/{version}/indicators/{indicatorId}")
    ScoringSourceBundle bundle(@PathVariable("version") String version, @PathVariable("indicatorId") String indicatorId);
}
