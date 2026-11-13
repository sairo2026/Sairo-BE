package com.sairo.be.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sairo.bootstrap.operator")
public record BootstrapOperatorProperties(
    boolean enabled, String email, String name, String invitationFile) {}
