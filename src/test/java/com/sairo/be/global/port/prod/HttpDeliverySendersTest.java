package com.sairo.be.global.port.prod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.port.SmsSender.SmsMessage;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class HttpDeliverySendersTest {
  private HttpServer server;
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> body = new AtomicReference<>();
  private volatile int status = 200;

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @Test
  void resendPostsMailWithBearerKey() throws Exception {
    new ResendMailSender(baseUrl(), "test-key", "from@example.test")
        .send(new MailMessage("to@example.test", "제목", "본문"));

    assertEquals("/emails", path.get());
    assertEquals("Bearer test-key", authorization.get());
    JsonNode json = JsonMapper.builder().build().readTree(body.get());
    assertEquals("from@example.test", json.get("from").asString());
    assertEquals("to@example.test", json.get("to").get(0).asString());
    assertEquals("제목", json.get("subject").asString());
    assertEquals("본문", json.get("text").asString());
  }

  @Test
  void resendRejectionFails() {
    status = 422;
    ResendMailSender sender = new ResendMailSender(baseUrl(), "test-key", "from@example.test");
    assertThrows(
        IllegalStateException.class,
        () -> sender.send(new MailMessage("to@example.test", "제목", "본문")));
  }

  @Test
  void solapiPostsSignedMessage() throws Exception {
    SolapiSmsSender sender = new SolapiSmsSender(baseUrl(), "key-1", "secret-1", "02-123-4567");
    sender.send(new SmsMessage("+82 10 1234 5678", "인증번호 안내"));

    assertEquals("/messages/v4/send", path.get());
    JsonNode message = JsonMapper.builder().build().readTree(body.get()).get("message");
    assertEquals("01012345678", message.get("to").asString());
    assertEquals("021234567", message.get("from").asString());
    assertEquals("인증번호 안내", message.get("text").asString());
    assertTrue(authorization.get().startsWith("HMAC-SHA256 apiKey=key-1, date="));
  }

  @Test
  void solapiSignatureIsHmacOfDateAndSalt() throws Exception {
    SolapiSmsSender sender = new SolapiSmsSender(baseUrl(), "key-1", "secret-1", "0212345678");
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    String header = sender.authorization(now, "salt-1");

    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("secret-1".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expected =
        java.util.HexFormat.of()
            .formatHex(mac.doFinal((now + "salt-1").getBytes(StandardCharsets.UTF_8)));
    assertTrue(header.endsWith("signature=" + expected));
    assertTrue(header.contains("salt=salt-1"));
  }

  @Test
  void solapiRejectionFailsAndBadNumberIsRefused() {
    SolapiSmsSender sender = new SolapiSmsSender(baseUrl(), "key-1", "secret-1", "0212345678");
    status = 400;
    assertThrows(
        IllegalStateException.class, () -> sender.send(new SmsMessage("010-1234-5678", "본문")));
    assertThrows(IllegalArgumentException.class, () -> SolapiSmsSender.toDomestic("02-1234-5678"));
  }

  @Test
  void providerGuardRequiresCredentialsForSelectedProvider() {
    DeliveryProviderGuard.validate("ses", "", "aws", true);
    DeliveryProviderGuard.validate("resend", "key", "solapi", false);
    DeliveryProviderGuard.validate("ses", "", "none", true);
    assertThrows(
        IllegalStateException.class,
        () -> DeliveryProviderGuard.validate("resend", "", "aws", true));
    assertThrows(
        IllegalStateException.class,
        () -> DeliveryProviderGuard.validate("ses", "", "solapi", true));
    assertThrows(
        IllegalStateException.class,
        () -> DeliveryProviderGuard.validate("other", "", "aws", true));
    assertThrows(
        IllegalStateException.class,
        () -> DeliveryProviderGuard.validate("ses", "", "other", true));
  }
}
