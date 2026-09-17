package com.riskwarning.processing.client;

import com.riskwarning.common.dto.retrieval.RetrievalBatchRequest;
import com.riskwarning.common.dto.retrieval.RetrievalBatchResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.riskwarning.processing.config.RetrievalFeignConfiguration;

@FeignClient(name = "retrieval-service", url = "${knowledge.service.url:http://localhost:8089}",
        path = "/retrieval", configuration = RetrievalFeignConfiguration.class)
public interface RetrievalClient {
    @PostMapping("/batch")
    RetrievalBatchResponse retrieve(@RequestBody RetrievalBatchRequest request);
}
