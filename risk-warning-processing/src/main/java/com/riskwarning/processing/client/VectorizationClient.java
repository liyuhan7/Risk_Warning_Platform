package com.riskwarning.processing.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(
    name = "knowledge-service",
    url = "${knowledge.service.url:http://localhost:8089}",
    path = "/test/vectorization"
)
public interface VectorizationClient {

    @PostMapping("/batch")
    Map<String, Object> batchVectorize(@RequestBody Map<String, List<String>> request);
}
