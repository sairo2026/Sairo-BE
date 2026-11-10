package com.sairo.be.domain.visit.service;

import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.PublicLinkProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ApproverPhoneHasher {

  private static final String PURPOSE = "approver-phone:";

  private final PublicLinkProperties properties;

  public ApproverPhoneHasher(PublicLinkProperties properties) {
    this.properties = properties;
  }

  public String hash(String normalizedPhone) {
    return HmacSupport.hmacHex(properties.hmacSecret(), PURPOSE + normalizedPhone);
  }
}
