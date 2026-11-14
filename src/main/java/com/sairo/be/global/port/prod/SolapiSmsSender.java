package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.SmsSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("prod")
@ConditionalOnExpression(
    "'${sairo.delivery.sms.provider:none}' == 'solapi'"
        + " and !'${sairo.delivery.sms.solapi.api-key:}'.isBlank()"
        + " and !'${sairo.delivery.sms.solapi.api-secret:}'.isBlank()"
        + " and !'${sairo.delivery.sms.solapi.sender-number:}'.isBlank()")
class SolapiSmsSender implements SmsSender {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final SecureRandom RANDOM = new SecureRandom();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI endpoint;
  private final String apiKey;
  private final String apiSecret;
  private final String senderNumber;

  SolapiSmsSender(
      @Value("${sairo.delivery.sms.solapi.base-url:https://api.solapi.com}") String baseUrl,
      @Value("${sairo.delivery.sms.solapi.api-key}") String apiKey,
      @Value("${sairo.delivery.sms.solapi.api-secret}") String apiSecret,
      @Value("${sairo.delivery.sms.solapi.sender-number}") String senderNumber) {
    this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/messages/v4/send");
    this.apiKey = apiKey;
    this.apiSecret = apiSecret;
    this.senderNumber = senderNumber.replaceAll("[^0-9]", "");
  }

  @Override
  public void send(SmsMessage message) {
    String payload =
        MAPPER.writeValueAsString(
            Map.of(
                "message",
                Map.of(
                    "to", toDomestic(message.toPhone()),
                    "from", senderNumber,
                    "text", message.body())));
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", authorization(Instant.now(), newSalt()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
    try {
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("문자 발송 요청이 거절되었습니다. 상태 " + response.statusCode());
      }
    } catch (IOException e) {
      throw new IllegalStateException("문자 발송 서비스에 연결할 수 없습니다.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("문자 발송이 중단되었습니다.", e);
    }
  }

  String authorization(Instant now, String salt) {
    String date = now.toString();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String signature =
          HexFormat.of().formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
      return "HMAC-SHA256 apiKey="
          + apiKey
          + ", date="
          + date
          + ", salt="
          + salt
          + ", signature="
          + signature;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("문자 발송 서명을 만들 수 없습니다.", e);
    }
  }

  private static String newSalt() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  static String toDomestic(String input) {
    String digits = input.replaceAll("[^0-9+]", "");
    if (digits.matches("01[0-9]{8,9}")) {
      return digits;
    }
    if (digits.matches("\\+82[0-9]{9,10}")) {
      return "0" + digits.substring(3);
    }
    throw new IllegalArgumentException("지원하지 않는 휴대폰 번호 형식입니다.");
  }
}
