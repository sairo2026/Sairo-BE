package com.sairo.be.global.port.prod;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
class DeliveryProviderGuard {
  DeliveryProviderGuard(
      @Value("${sairo.delivery.mail.provider:ses}") String mailProvider,
      @Value("${sairo.delivery.mail.resend.api-key:}") String resendApiKey,
      @Value("${sairo.delivery.sms.provider:aws}") String smsProvider,
      @Value("${sairo.delivery.sms.solapi.api-key:}") String solapiApiKey,
      @Value("${sairo.delivery.sms.solapi.api-secret:}") String solapiApiSecret,
      @Value("${sairo.delivery.sms.solapi.sender-number:}") String solapiSenderNumber) {
    validate(
        mailProvider,
        resendApiKey,
        smsProvider,
        solapiApiKey.isBlank() || solapiApiSecret.isBlank() || solapiSenderNumber.isBlank());
  }

  static void validate(
      String mailProvider, String resendApiKey, String smsProvider, boolean solapiIncomplete) {
    if (!Set.of("ses", "resend").contains(mailProvider)) {
      throw new IllegalStateException("지원하지 않는 메일 공급자입니다.");
    }
    if (mailProvider.equals("resend") && resendApiKey.isBlank()) {
      throw new IllegalStateException("메일 공급자 resend에는 API 키가 필요합니다.");
    }
    if (!Set.of("aws", "solapi").contains(smsProvider)) {
      throw new IllegalStateException("지원하지 않는 문자 공급자입니다.");
    }
    if (smsProvider.equals("solapi") && solapiIncomplete) {
      throw new IllegalStateException("문자 공급자 solapi에는 API 키, 비밀 값, 발신번호가 필요합니다.");
    }
  }
}
