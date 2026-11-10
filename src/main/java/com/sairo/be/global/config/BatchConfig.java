package com.sairo.be.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Profile("!migrate")
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "sairo.batch.enabled", havingValue = "true")
public class BatchConfig {}
