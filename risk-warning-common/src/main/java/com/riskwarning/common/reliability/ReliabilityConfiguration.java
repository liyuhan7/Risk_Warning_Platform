package com.riskwarning.common.reliability;

import com.riskwarning.common.observability.AssessmentFlowLogger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableConfigurationProperties(DurableWorkProperties.class)
@ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled", havingValue = "true")
public class ReliabilityConfiguration {

    @Bean
    public ReliabilitySchemaGuard reliabilitySchemaGuard(JdbcTemplate jdbcTemplate) {
        ReliabilitySchemaGuard guard = new ReliabilitySchemaGuard(jdbcTemplate);
        guard.verify();
        return guard;
    }

    @Bean
    public DurableWorkStore durableWorkStore(JdbcTemplate jdbcTemplate,
                                             TransactionTemplate transactionTemplate,
                                             DurableWorkProperties properties,
                                             ReliabilitySchemaGuard schemaGuard) {
        properties.validate();
        return new DurableWorkStore(jdbcTemplate, transactionTemplate, properties);
    }

    @Bean
    public DurableWorker durableWorker(DurableWorkStore store,
                                       ObjectProvider<DurableWorkHandler> handlers,
                                       DurableWorkProperties properties,
                                       AssessmentFlowLogger flowLogger) {
        List<DurableWorkHandler> handlerList = handlers.orderedStream()
                .collect(Collectors.toList());
        return new DurableWorker(store, handlerList, properties, flowLogger);
    }
}
