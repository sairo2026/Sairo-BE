package com.sairo.be.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sairo.security")
public record SecurityProperties(
    String ticketSecret,
    String rateLimitHmacSecret,
    String kakaoKeyHmacSecret,
    int kakaoKeyHmacVersion,
    String unsubscribeSecret,
    String kakaoKeyEncryptionSecret) {}
