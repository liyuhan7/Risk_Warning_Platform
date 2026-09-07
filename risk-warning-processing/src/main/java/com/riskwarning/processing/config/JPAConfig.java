package com.riskwarning.processing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.persistence.EntityManagerFactory;

@Configuration
public class JPAConfig {

    /**
     * Bean 名必须是 transactionManager：Spring Data 仓库代理按该默认名解析
     * 事务管理器（@EnableJpaRepositories 的 transactionManagerRef 默认值），
     * 改名会导致 repository 写操作在运行期找不到事务管理器。
     */
    @Bean("transactionManager")
    @Primary
    public PlatformTransactionManager jpaTransactionManager(
            EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
