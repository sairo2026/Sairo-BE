package com.sairo.be.global.port.toss;

import com.sairo.be.global.port.PaymentGateway;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Profile("!migrate")
@ConditionalOnExpression("'${sairo.toss.secret-key:}'.trim() != ''")
@EnableConfigurationProperties(TossProperties.class)
@Component
public class TossPaymentGateway implements PaymentGateway {

  private static final Set<String> FAILED_PAYMENT_STATUSES =
      Set.of("CANCELED", "ABORTED", "EXPIRED");
  private static final Set<String> UNDETERMINED_CODES =
      Set.of(
          "IDEMPOTENT_REQUEST_PROCESSING",
          "FAILED_INTERNAL_SYSTEM_PROCESSING",
          "UNKNOWN_PAYMENT_ERROR");

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String authorization;
  private final Duration requestTimeout;
  private final Duration approvalTimeout;

  public TossPaymentGateway(TossProperties properties, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.baseUrl = stripTrailingSlash(properties.baseUrl());
    this.authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));
    this.requestTimeout = orDefault(properties.requestTimeout(), Duration.ofSeconds(15));
    this.approvalTimeout = orDefault(properties.approvalTimeout(), Duration.ofSeconds(65));
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(orDefault(properties.connectTimeout(), Duration.ofSeconds(5)))
            .build();
  }

  @Override
  public BillingKeyResult issueBillingKey(String authKey, String customerKey) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("authKey", authKey);
    body.put("customerKey", customerKey);
    Reply reply = post("/v1/billing/authorizations/issue", body, null, requestTimeout);
    if (reply.status() != 200 || reply.body().path("billingKey").asString("").isBlank()) {
      throw new BillingKeyIssueException(reply.code("BILLING_KEY_ISSUE_FAILED"));
    }
    JsonNode card = reply.body().path("card");
    String issuer = card.path("issuerCode").asString("");
    if (issuer.isBlank()) {
      issuer = reply.body().path("cardCompany").asString("");
    }
    String number = card.path("number").asString("");
    if (number.isBlank()) {
      number = reply.body().path("cardNumber").asString("");
    }
    return new BillingKeyResult(reply.body().path("billingKey").asString(), issuer, number);
  }

  @Override
  public void deleteBillingKey(String billingKey) {
    Reply reply = send(newRequest("/v1/billing/" + encode(billingKey), requestTimeout).DELETE());
    if (reply.status() != 200 && reply.status() != 404) {
      throw new TossGatewayException("billing key delete failed: " + reply.code("UNKNOWN"));
    }
  }

  @Override
  public PaymentResult charge(ChargeCommand command) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amount", command.amount());
    body.put("customerKey", command.customerKey());
    body.put("orderId", command.orderId());
    body.put("orderName", command.orderName());
    Reply reply =
        post(
            "/v1/billing/" + encode(command.billingKey()),
            body,
            command.idempotencyKey(),
            approvalTimeout);
    if (reply.status() == 200) {
      String paymentKey = reply.body().path("paymentKey").asString(null);
      if ("DONE".equals(reply.body().path("status").asString(""))) {
        return new PaymentResult(PaymentStatus.SUCCEEDED, paymentKey, null);
      }
      return new PaymentResult(PaymentStatus.VERIFYING, paymentKey, null);
    }
    return failureOrVerifying(reply, null);
  }

  @Override
  public PaymentResult cancel(String paymentKey, String reason) {
    return cancel(new CancelCommand(paymentKey, reason, null, null));
  }

  @Override
  public PaymentResult cancel(CancelCommand command) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cancelReason", command.reason());
    if (command.amount() != null) {
      body.put("cancelAmount", command.amount());
    }
    Reply reply =
        post(
            "/v1/payments/" + encode(command.paymentKey()) + "/cancel",
            body,
            command.idempotencyKey(),
            requestTimeout);
    if (reply.status() == 200) {
      String paymentKey = reply.body().path("paymentKey").asString(command.paymentKey());
      return new PaymentResult(PaymentStatus.SUCCEEDED, paymentKey, null);
    }
    return failureOrVerifying(reply, command.paymentKey());
  }

  @Override
  public PaymentResult inquire(String orderId) {
    Reply reply = send(newRequest("/v1/payments/orders/" + encode(orderId), requestTimeout).GET());
    if (reply.status() == 404) {
      return new PaymentResult(PaymentStatus.FAILED, null, "NOT_FOUND");
    }
    if (reply.status() != 200) {
      return failureOrVerifying(reply, null);
    }
    String status = reply.body().path("status").asString("");
    String paymentKey = reply.body().path("paymentKey").asString(null);
    if ("DONE".equals(status)) {
      return new PaymentResult(PaymentStatus.SUCCEEDED, paymentKey, null);
    }
    if (FAILED_PAYMENT_STATUSES.contains(status)) {
      return new PaymentResult(PaymentStatus.FAILED, paymentKey, status);
    }
    return new PaymentResult(PaymentStatus.VERIFYING, paymentKey, null);
  }

  private PaymentResult failureOrVerifying(Reply reply, String paymentKey) {
    boolean undetermined =
        reply.status() >= 500
            || reply.status() == 429
            || UNDETERMINED_CODES.contains(reply.code(""));
    if (undetermined) {
      return new PaymentResult(PaymentStatus.VERIFYING, paymentKey, null);
    }
    return new PaymentResult(PaymentStatus.FAILED, paymentKey, reply.code("UNKNOWN"));
  }

  private Reply post(String path, Object body, String idempotencyKey, Duration timeout) {
    String json = objectMapper.writeValueAsString(body);
    HttpRequest.Builder builder =
        newRequest(path, timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      builder.header("Idempotency-Key", idempotencyKey);
    }
    return send(builder);
  }

  private HttpRequest.Builder newRequest(String path, Duration timeout) {
    return HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("Authorization", authorization)
        .header("Accept", "application/json");
  }

  private Reply send(HttpRequest.Builder builder) {
    try {
      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new Reply(response.statusCode(), parse(response.body()));
    } catch (IOException e) {
      throw new TossGatewayException("payment gateway call failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TossGatewayException("payment gateway call interrupted", e);
    }
  }

  private JsonNode parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(raw);
    } catch (RuntimeException e) {
      return objectMapper.createObjectNode();
    }
  }

  private static String encode(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static Duration orDefault(Duration value, Duration fallback) {
    return value == null ? fallback : value;
  }

  private record Reply(int status, JsonNode body) {

    String code(String fallback) {
      String code = body.path("code").asString("");
      return code.isBlank() ? fallback : code;
    }
  }
}
