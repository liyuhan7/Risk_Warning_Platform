package com.riskwarning.bert.controller;

import com.riskwarning.bert.service.PythonServiceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;


@Slf4j
@RestController
@RequestMapping("/bert")
@RequiredArgsConstructor
public class BertServiceController {

    private final PythonServiceManager pythonServiceManager;

   
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("javaService", "running");
        status.put("pythonService", pythonServiceManager.isServiceRunning() ? "running" : "stopped");
        status.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(status);
    }

    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("pythonServiceRunning", pythonServiceManager.isServiceRunning());
        return ResponseEntity.ok(health);
    }
}

