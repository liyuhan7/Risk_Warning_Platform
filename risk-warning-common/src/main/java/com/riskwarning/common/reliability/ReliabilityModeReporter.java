package com.riskwarning.common.reliability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 在服务启动时输出可靠链实际模式，避免开关回退后静默运行兼容链。 */
@Component
@Slf4j
public class ReliabilityModeReporter implements ApplicationListener<ContextRefreshedEvent> {

    private final Environment environment;
    private boolean reported;

    public ReliabilityModeReporter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public synchronized void onApplicationEvent(ContextRefreshedEvent event) {
        if (reported || event.getApplicationContext().getParent() != null) {
            return;
        }
        reported = true;
        // @ConditionalOnProperty 按 setValue 忽略大小写比较，"1"/"yes" 等取值会让
        // 新旧消费者条件同时落空导致 topic 无人消费，必须在启动期拒绝这类配置。
        String raw = environment.getProperty("assessment.reliability.enabled");
        if (raw != null && !"true".equals(raw) && !"false".equals(raw)) {
            throw new IllegalStateException("assessment.reliability.enabled 取值无效: '" + raw
                    + "'，仅支持 true 或 false，否则新旧消费者可能都未注册");
        }
        boolean enabled = environment.getProperty(
                "assessment.reliability.enabled", Boolean.class, Boolean.FALSE);
        String namespace = environment.getProperty(
                "assessment.reliability.namespace", event.getApplicationContext().getId());
        if (enabled) {
            log.info("[可靠链模式] ENABLED namespace={}，Durable Inbox/Outbox/Worker 已启用，"
                    + "数据库迁移 009/010/011 已通过启动校验", namespace);
        } else {
            log.warn("[可靠链模式] COMPATIBLE namespace={}，显式关闭可靠链，当前运行兼容消费者与"
                    + "AfterCommit Kafka 投递", namespace);
        }
    }
}
