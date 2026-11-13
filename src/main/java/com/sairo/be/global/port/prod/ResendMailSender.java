package com.sairo.be.global.port.prod;

import com.sairo.be.global.port.MailSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("prod")
@ConditionalOnExpression(
    "'${sairo.delivery.mail.provider:ses}' == 'resend'"
        + " and !'${sairo.delivery.mail.resend.api-key:}'.isBlank()")
class ResendMailSender implements MailSender {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final URI endpoint;
  private final String apiKey;
  private final String from;

  ResendMailSender(
      @Value("${sairo.delivery.mail.resend.base-url:https://api.resend.com}") String baseUrl,
      @Value("${sairo.delivery.mail.resend.api-key}") String apiKey,
      @Value("${sairo.delivery.mail.from}") String from) {
    this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/emails");
    this.apiKey = apiKey;
    this.from = from;
  }

  @Override
  public void send(MailMessage message) {
    String payload =
        MAPPER.writeValueAsString(
            Map.of(
                "from", from,
                "to", List.of(message.to()),
                "subject", message.subject(),
                "text", message.body()));
    HttpRequest request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
    try {
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() / 100 != 2) {
        throw new IllegalStateException("메일 발송 요청이 거절되었습니다. 상태 " + response.statusCode());
      }
    } catch (IOException e) {
      throw new IllegalStateException("메일 발송 서비스에 연결할 수 없습니다.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("메일 발송이 중단되었습니다.", e);
    }
  }
}
