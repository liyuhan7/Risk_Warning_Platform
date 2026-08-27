package com.riskwarning.bert.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PythonServiceManager {

    @Value("${bert.python.path:python}")
    private String pythonPath;

    @Value("${bert.python.script:bert-service/bert_service.py}")
    private String pythonScript;

    @Value("${bert.service.port:8000}")
    private int servicePort;

    @Value("${bert.service.host:0.0.0.0}")
    private String serviceHost;

    @Value("${bert.model.name:bert-base-chinese}")
    private String modelName;

    @Value("${bert.service.enabled:true}")
    private boolean serviceEnabled;

    @Value("${bert.service.healthcheck.enabled:true}")
    private boolean healthcheckEnabled;

    @Value("${bert.service.healthcheck.interval:5000}")
    private long healthcheckInterval;

    private Process pythonProcess;
    private Thread healthcheckThread;
    private volatile boolean running = false;
    
    private final RestTemplate restTemplate = new RestTemplate();
    
    private String getPythonServiceUrl() {
        return "http://localhost:" + servicePort;
    }

    
    public void startService() {
        if (!serviceEnabled) {
            log.info("Python服务已禁用，跳过启动");
            return;
        }

        try {
            // 先停止旧服务（如果存在）
            stopOldService();
            
            File scriptFile = findScriptFile();
            if (!scriptFile.exists()) {
                throw new RuntimeException("Python脚本不存在: " + scriptFile.getAbsolutePath());
            }

            // pythonPath 支持 "py -3.9" 等带参数形式，按空白拆分为命令与参数
            List<String> command = new ArrayList<>(Arrays.asList(pythonPath.trim().split("\\s+")));
            command.add(scriptFile.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(scriptFile.getParentFile());
            pb.redirectErrorStream(true);
            
            // 设置环境变量
            pb.environment().put("PORT", String.valueOf(servicePort));
            pb.environment().put("HOST", serviceHost);
            pb.environment().put("BERT_MODEL_NAME", modelName);
            pb.environment().put("PYTHONUNBUFFERED", "1");

            pythonProcess = pb.start();
            running = true;

            startLogReader();
            waitForServiceReady();
            
            if (healthcheckEnabled) {
                startHealthcheck();
            }

            log.info("Python服务启动成功，端口: {}", servicePort);
        } catch (Exception e) {
            log.error("启动Python服务失败", e);
            throw new RuntimeException("启动Python服务失败: " + e.getMessage(), e);
        }
    }
    
    private void stopOldService() {
        try {
            // 检查端口是否被占用
            java.net.ServerSocket socket = new java.net.ServerSocket();
            try {
                socket.bind(new java.net.InetSocketAddress("localhost", servicePort), 1);
                socket.close();
                log.debug("端口 {} 未被占用", servicePort);
            } catch (java.net.BindException e) {
                log.warn("端口 {} 已被占用，尝试终止占用进程", servicePort);
                // 端口被占用，尝试找到并终止占用进程
                try {
                    Process findProcess = new ProcessBuilder(
                        "cmd", "/c", 
                        "netstat -ano | findstr :" + servicePort + " | findstr LISTENING"
                    ).start();
                    
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(findProcess.getInputStream())
                    );
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length > 0) {
                            try {
                                String pid = parts[parts.length - 1];
                                int processId = Integer.parseInt(pid);
                                log.info("终止占用端口的进程: {}", processId);
                                Runtime.getRuntime().exec("taskkill /F /PID " + processId);
                                Thread.sleep(1000); // 等待进程终止
                            } catch (Exception ex) {
                                log.debug("无法终止进程: {}", ex.getMessage());
                            }
                        }
                    }
                    reader.close();
                } catch (Exception ex) {
                    log.warn("无法终止占用端口的进程: {}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("检查端口占用时出错: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stopService() {
        if (healthcheckThread != null && healthcheckThread.isAlive()) {
            healthcheckThread.interrupt();
        }

        if (pythonProcess != null && pythonProcess.isAlive()) {
            running = false;
            try {
                pythonProcess.destroy();
                if (!pythonProcess.waitFor(10, TimeUnit.SECONDS)) {
                    pythonProcess.destroyForcibly();
                    pythonProcess.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pythonProcess.destroyForcibly();
            }
        }
    }

    public boolean isServiceRunning() {
        return running && pythonProcess != null && pythonProcess.isAlive();
    }

    private File findScriptFile() {
        String userDir = System.getProperty("user.dir");
        String scriptName = new File(pythonScript).getName();
        
        // 尝试的路径列表（按优先级）
        List<String> paths = Arrays.asList(
            pythonScript,  // 配置的相对路径
            userDir + "/" + pythonScript,  // 相对于工作目录
            userDir + "/risk-warning-bert/bert-service/" + scriptName,  // 从项目根目录
            userDir + "/bert-service/" + scriptName  // 当前就是模块目录
        );
        
        // 尝试通过类路径定位模块目录
        String moduleDir = getModuleDir();
        if (moduleDir != null) {
            paths.add(0, moduleDir + "/bert-service/" + scriptName);
        }

        for (String path : paths) {
            File file = new File(path);
            if (file.exists() && file.isFile()) {
                log.info("找到Python脚本: {}", file.getAbsolutePath());
                return file.getAbsoluteFile();
            }
        }

        throw new RuntimeException("找不到Python脚本: " + pythonScript + 
            "，已尝试路径: " + String.join(", ", paths));
    }

    private String getModuleDir() {
        try {
            String classPath = this.getClass().getProtectionDomain()
                .getCodeSource().getLocation().getPath();
            classPath = java.net.URLDecoder.decode(classPath, "UTF-8");
            
            // Windows路径处理
            if (classPath.startsWith("/") && classPath.length() > 1 && classPath.charAt(2) == ':') {
                classPath = classPath.substring(1);
            }
            
            File classFile = new File(classPath);
            if (classFile.getAbsolutePath().contains("target/classes")) {
                File moduleDir = classFile.getParentFile().getParentFile().getParentFile();
                if (moduleDir.exists()) {
                    return moduleDir.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            log.debug("无法定位模块目录: {}", e.getMessage());
        }
        return null;
    }

    private void startLogReader() {
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(pythonProcess.getInputStream()))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    log.info("[Python-Service] {}", line);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("读取Python服务日志失败", e);
                }
            }
        });
        logThread.setDaemon(true);
        logThread.setName("python-service-log-reader");
        logThread.start();
    }

    private void waitForServiceReady() {
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts && running; i++) {
            try {
                if (checkHealth()) {
                    log.info("Python服务已就绪");
                    return;
                }
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待服务就绪时被中断", e);
            }
        }
        throw new RuntimeException("Python服务启动超时，60秒内未就绪");
    }

    private boolean checkHealth() {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("http://localhost:" + servicePort + "/health").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void startHealthcheck() {
        healthcheckThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(healthcheckInterval);
                    if (!checkHealth()) {
                        log.warn("Python服务健康检查失败");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("健康检查异常", e);
                }
            }
        });
        healthcheckThread.setDaemon(true);
        healthcheckThread.setName("python-service-healthcheck");
        healthcheckThread.start();
    }
    
    // ==================== 分类服务方法 ====================
    
    public Map<String, Object> checkClassifierHealth() {
        String url = getPythonServiceUrl() + "/classify/health";
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
        return response.getBody();
    }

    public Map<String, Object> classify(Map<String, Object> request) {
        String url = getPythonServiceUrl() + "/classify";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
        return response.getBody();
    }

    public Map<String, Object> classifyBatch(Map<String, Object> request) {
        String url = getPythonServiceUrl() + "/classify/batch";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        
        ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
        return response.getBody();
    }
}

