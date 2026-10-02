package com.riskwarning.bert;

import com.riskwarning.bert.service.PythonServiceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.riskwarning.common.exception.GlobalExceptionHandler;
import com.riskwarning.common.exception.ApiErrorController;


@Slf4j
@Import({GlobalExceptionHandler.class, ApiErrorController.class})
@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
    org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
    // 如果不需要Nacos，可以排除这些自动配置
    // com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration.class,
    // com.alibaba.cloud.nacos.endpoint.NacosConfigEndpointAutoConfiguration.class
})
public class BertApplication {

    public static void main(String[] args) {
        SpringApplication.run(BertApplication.class, args);
    }


    @Bean
    public CommandLineRunner startPythonService(PythonServiceManager pythonServiceManager) {
        return args -> {
            pythonServiceManager.startService();
        };
    }
}

