package com.riskwarning.processing.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(
    name = "bert-service",
    url = "${bert.service.url:http://localhost:8090}",
    path = "/classify"
)
public interface ClassifierClient {

    @PostMapping("/batch")
    Map<String, Object> classifyBatch(@RequestBody Map<String, Object> request);

}
