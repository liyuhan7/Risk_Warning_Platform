package com.riskwarning.gateway;

import com.riskwarning.common.utils.JwtUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

/**
 * 网关服务启动类
 *
 * 只显式引入 JwtUtils 供 AuthFilter 使用，不整体扫描 common.utils 包，
 * 避免把与网关无关的工具 Bean 带入网关上下文
 */
@SpringBootApplication(scanBasePackages = "com.riskwarning.gateway")
@Import(JwtUtils.class)
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
