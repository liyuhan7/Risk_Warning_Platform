package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.utils.KafkaUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Outbox 装配：可靠链开启时消息与业务事务同库提交，未开启时保持兼容投递。
 * 两个实现由同一开关互斥，业务代码只依赖 KafkaOutbox 接口。
 */
@Configuration
public class KafkaOutboxConfiguration {

    @Bean
    public KafkaOutboxCodec kafkaOutboxCodec() {
        return new KafkaOutboxCodec();
    }

    @Bean
    @ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled", havingValue = "true")
    public KafkaOutbox transactionalKafkaOutbox(DurableWorkStore durableWorkStore,
                                                KafkaOutboxCodec kafkaOutboxCodec,
                                                AssessmentFlowLogger flowLogger) {
        return new TransactionalKafkaOutbox(durableWorkStore, kafkaOutboxCodec, flowLogger);
    }

    @Bean
    @ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    public KafkaOutbox afterCommitKafkaOutbox(KafkaUtils kafkaUtils,
                                              AssessmentFlowLogger flowLogger) {
        return new AfterCommitKafkaOutbox(kafkaUtils, flowLogger);
    }

    /** 未启用可靠链时闲置，启用后由 DurableWorker 领取 KAFKA_OUTBOX 任务。 */
    @Bean
    public KafkaOutboxHandler kafkaOutboxHandler(KafkaUtils kafkaUtils, KafkaOutboxCodec kafkaOutboxCodec) {
        return new KafkaOutboxHandler(kafkaUtils, kafkaOutboxCodec);
    }
}
