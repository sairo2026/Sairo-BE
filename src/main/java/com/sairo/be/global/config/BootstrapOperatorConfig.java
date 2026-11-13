package com.sairo.be.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!migrate")
@Configuration
@EnableConfigurationProperties(BootstrapOperatorProperties.class)
public class BootstrapOperatorConfig {}
