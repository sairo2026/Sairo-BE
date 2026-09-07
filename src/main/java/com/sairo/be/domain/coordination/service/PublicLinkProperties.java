package com.sairo.be.domain.coordination.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sairo.public-link")
public record PublicLinkProperties(String hmacSecret) {}
