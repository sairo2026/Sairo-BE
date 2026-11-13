package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.MessageType;
import software.amazon.awssdk.services.pinpointsmsvoicev2.model.SendTextMessageRequest;

@Component
@Profile("prod")
class AwsSmsSender implements SmsSender {
  private final PinpointSmsVoiceV2Client client;
  private final String originationIdentity;

  AwsSmsSender(
      PinpointSmsVoiceV2Client client,
      @Value("${sairo.delivery.sms.origination-identity:}") String originationIdentity) {
    this.client = client;
    this.originationIdentity = originationIdentity;
  }

  @Override
  public void send(SmsMessage message) {
    var request =
        SendTextMessageRequest.builder()
            .destinationPhoneNumber(toE164(message.toPhone()))
            .messageBody(message.body())
            .messageType(MessageType.TRANSACTIONAL);
    if (!originationIdentity.isBlank()) {
      request.originationIdentity(originationIdentity);
    }
    client.sendTextMessage(request.build());
  }

  static String toE164(String input) {
    String digits = input.replaceAll("[^0-9+]", "");
    if (digits.matches("01[0-9]{8,9}")) {
      return "+82" + digits.substring(1);
    }
    if (digits.matches("\\+82[0-9]{9,10}")) {
      return digits;
    }
    throw new IllegalArgumentException("지원하지 않는 휴대폰 번호 형식입니다.");
  }
}
