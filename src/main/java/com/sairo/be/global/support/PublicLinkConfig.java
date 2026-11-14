package com.sairo.be.global.support;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("!migrate")
@Configuration
@EnableConfigurationProperties(PublicLinkProperties.class)
public class PublicLinkConfig {}
