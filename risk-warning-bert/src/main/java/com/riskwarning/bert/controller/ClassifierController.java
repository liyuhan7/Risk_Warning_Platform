package com.riskwarning.bert.controller;

import com.riskwarning.bert.service.PythonServiceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/classify")
@RequiredArgsConstructor
public class ClassifierController {

    private final PythonServiceManager pythonServiceManager;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        if (!pythonServiceManager.isServiceRunning()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Python服务未运行");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        }
        
        try {
            Map<String, Object> result = pythonServiceManager.checkClassifierHealth();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("调用分类服务健康检查失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "调用分类服务失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> classify(@RequestBody Map<String, Object> request) {
        if (!pythonServiceManager.isServiceRunning()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Python服务未运行");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        }
        
        try {
            Map<String, Object> result = pythonServiceManager.classify(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("调用分类服务失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "调用分类服务失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> classifyBatch(@RequestBody Map<String, Object> request) {
        if (!pythonServiceManager.isServiceRunning()) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Python服务未运行");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error);
        }
        
        try {
            Map<String, Object> result = pythonServiceManager.classifyBatch(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("调用批量分类服务失败", e);
            Map<String, Object> error = new HashMap<>();
            error.put("error", "调用批量分类服务失败: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}

