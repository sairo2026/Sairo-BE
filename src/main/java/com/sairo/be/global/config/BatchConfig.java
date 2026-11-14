package com.sairo.be.global.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Profile("!migrate")
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = BatchConfig.DEFAULT_LOCK_AT_MOST_FOR)
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class BatchConfig {

  public static final String DEFAULT_LOCK_AT_MOST_FOR = "PT30M";
  public static final String LOCK_TABLE = "shedlock";

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .withTableName(LOCK_TABLE)
            .usingDbTime()
            .build());
  }
}
