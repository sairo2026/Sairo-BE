package com.sairo.be.domain.auth.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao")
public record KakaoProperties(String restApiKey, String clientSecret, String redirectUri) {}
