package com.sairo.be.domain.coordination.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

// Excluded from the "migrate" profile like the rest of the coordination stack: that
// profile has no PUBLIC_LINK_HMAC_SECRET requirement (see application-migrate.yml).
@Profile("!migrate")
@Configuration
@EnableConfigurationProperties(PublicLinkProperties.class)
public class PublicLinkConfig {}
