package com.sairo.be.domain.auth.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

// Excluded from the "migrate" profile like the rest of the Kakao login stack: that
// profile has no KAKAO_* environment variables available (see application-migrate.yml).
@Profile("!migrate")
@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoAuthConfig {}
