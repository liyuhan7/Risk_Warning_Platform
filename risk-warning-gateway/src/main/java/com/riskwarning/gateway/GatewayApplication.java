package com.riskwarning.gateway;

import com.riskwarning.common.utils.JwtUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 网关服务启动类
 *
 * 只显式引入 JwtUtils 供 AuthFilter 使用，不整体扫描 common.utils 包——
 * 该包内的 LLMUtil 注册依赖 llm.enabled=true 时的 AiChatProvider Bean，
 * gateway 不在 Provider 的扫描范围内，整包扫描会在该变量全局可见时启动失败
 */
@SpringBootApplication(scanBasePackages = "com.riskwarning.gateway")
@Import(JwtUtils.class)
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
