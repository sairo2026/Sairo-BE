package com.sairo.be.global.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sairo.public-link")
public record PublicLinkProperties(String hmacSecret) {}
