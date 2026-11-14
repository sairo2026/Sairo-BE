package com.sairo.be.global.support;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PhoneVerificationRequirement {

  private static final String NONE = "none";

  private final boolean required;

  public PhoneVerificationRequirement(
      @Value("${sairo.delivery.sms.provider:}") String smsProvider) {
    this.required = !NONE.equalsIgnoreCase(smsProvider == null ? "" : smsProvider.trim());
  }

  public boolean isRequired() {
    return required;
  }

  public void requireSmsAvailable() {
    if (!required) {
      throw new BusinessException(ErrorCode.SMS_UNAVAILABLE);
    }
  }
}
