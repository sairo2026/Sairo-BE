package com.sairo.be.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sairo.company")
public record CompanyProperties(
    String businessName,
    String representativeName,
    String businessNumber,
    String address,
    String contactPhone,
    String ecommerceNotice,
    String supportEmail,
    String privacyContact,
    String privacyRequestPath) {}
