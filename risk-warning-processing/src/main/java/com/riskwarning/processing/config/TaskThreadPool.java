package com.riskwarning.processing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TaskThreadPool {

    @Bean(name = "FileProcessTaskThreadPool")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);  // 核心线程数
        executor.setMaxPoolSize(10);  // 最大线程数
        executor.setQueueCapacity(20);  // 等待队列容量
        executor.setThreadNamePrefix("File-");  // 线程名称前缀
        return executor;
    }

    @Bean(name = "BehaviorProcessTaskThreadPool")
    public ThreadPoolTaskExecutor behaviorProcessTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);  // 核心线程数
        executor.setMaxPoolSize(50);  // 最大线程数
        executor.setQueueCapacity(500);  // 等待队列容量提高到 500
        executor.setThreadNamePrefix("Behavior-");  // 线程名称前缀
        executor.setKeepAliveSeconds(60);  // 空闲线程存活时间
        // 设置拒绝策略为 CallerRunsPolicy，提供回压机制
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);  // 关闭时等待任务完成
        executor.setAwaitTerminationSeconds(60);  // 等待时间
        return executor;
    }
}
