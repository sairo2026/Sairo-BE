package com.sairo.be.global.port.toss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sairo.be.global.port.PaymentGateway.BillingKeyIssueException;
import com.sairo.be.global.port.PaymentGateway.BillingKeyResult;
import com.sairo.be.global.port.PaymentGateway.CancelCommand;
import com.sairo.be.global.port.PaymentGateway.ChargeCommand;
import com.sairo.be.global.port.PaymentGateway.PaymentResult;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TossPaymentGatewayTest {

  private record Captured(
      String method, String path, String authorization, String idempotencyKey, String body) {}

  private HttpServer server;
  private final List<Captured> captured = new CopyOnWriteArrayList<>();
  private volatile int nextStatus = 200;
  private volatile String nextBody = "{}";
  private TossPaymentGateway gateway;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          captured.add(
              new Captured(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getRawPath(),
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                  body));
          byte[] bytes = nextBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(nextStatus, bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    gateway = gatewayFor("http://127.0.0.1:" + server.getAddress().getPort() + "/");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static TossPaymentGateway gatewayFor(String baseUrl) {
    return new TossPaymentGateway(
        new TossProperties(
            "test_sk_fake", "", baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(2), null),
        JsonMapper.builder().build());
  }

  private void respond(int status, String body) {
    nextStatus = status;
    nextBody = body;
  }

  private Captured last() {
    return captured.get(captured.size() - 1);
  }

  @Test
  void 빌링키_발급은_인증키와_고객키를_보내고_카드_정보를_돌려준다() {
    respond(
        200,
        "{\"billingKey\":\"bk_1\",\"customerKey\":\"ck\",\"card\":{\"issuerCode\":\"11\","
            + "\"number\":\"43301234****123*\"}}");

    BillingKeyResult result = gateway.issueBillingKey("auth-1", "customer-key-0000000001");

    assertThat(result.billingKey()).isEqualTo("bk_1");
    assertThat(result.cardIssuer()).isEqualTo("11");
    assertThat(result.maskedCardNumber()).isEqualTo("43301234****123*");
    assertThat(last().method()).isEqualTo("POST");
    assertThat(last().path()).isEqualTo("/v1/billing/authorizations/issue");
    assertThat(last().authorization())
        .isEqualTo(
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("test_sk_fake:".getBytes(StandardCharsets.UTF_8)));
    assertThat(last().body())
        .contains("\"authKey\":\"auth-1\"")
        .contains("\"customerKey\":\"customer-key-0000000001\"");
  }

  @Test
  void 빌링키_발급_실패는_오류코드를_담은_예외로_바뀐다() {
    respond(400, "{\"code\":\"INVALID_AUTH_KEY\",\"message\":\"bad\"}");

    assertThatThrownBy(() -> gateway.issueBillingKey("auth-1", "customer-key-0000000001"))
        .isInstanceOf(BillingKeyIssueException.class)
        .hasMessage("INVALID_AUTH_KEY");
  }

  @Test
  void 자동결제_승인은_멱등키_헤더와_주문_정보를_보낸다() {
    respond(200, "{\"paymentKey\":\"pk_1\",\"status\":\"DONE\",\"orderId\":\"order-000001\"}");

    PaymentResult result =
        gateway.charge(
            new ChargeCommand(
                "bk/1", "customer-key-0000000001", "order-000001", 39_900, "사이로 월 구독", "idem-1"));

    assertThat(result).isEqualTo(new PaymentResult(PaymentStatus.SUCCEEDED, "pk_1", null));
    assertThat(last().path()).isEqualTo("/v1/billing/bk%2F1");
    assertThat(last().idempotencyKey()).isEqualTo("idem-1");
    assertThat(last().body())
        .contains("\"amount\":39900")
        .contains("\"orderId\":\"order-000001\"")
        .contains("\"orderName\":\"사이로 월 구독\"")
        .contains("\"customerKey\":\"customer-key-0000000001\"");
  }

  @Test
  void 자동결제_승인이_거절되면_실패와_오류코드를_돌려준다() {
    respond(403, "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도 초과\"}");

    PaymentResult result =
        gateway.charge(new ChargeCommand("bk", "ck", "order-000001", 100, "상품", "idem-2"));

    assertThat(result)
        .isEqualTo(new PaymentResult(PaymentStatus.FAILED, null, "REJECT_CARD_PAYMENT"));
  }

  @Test
  void 서버_오류와_처리_중_응답은_확인_중으로_본다() {
    respond(500, "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\"}");
    assertThat(
            gateway
                .charge(new ChargeCommand("bk", "ck", "order-000001", 100, "상품", "idem-3"))
                .status())
        .isEqualTo(PaymentStatus.VERIFYING);

    respond(409, "{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\"}");
    assertThat(
            gateway
                .charge(new ChargeCommand("bk", "ck", "order-000001", 100, "상품", "idem-3"))
                .status())
        .isEqualTo(PaymentStatus.VERIFYING);

    respond(200, "{\"paymentKey\":\"pk\",\"status\":\"IN_PROGRESS\"}");
    assertThat(
            gateway
                .charge(new ChargeCommand("bk", "ck", "order-000001", 100, "상품", "idem-3"))
                .status())
        .isEqualTo(PaymentStatus.VERIFYING);
  }

  @Test
  void 연결할_수_없으면_예외를_던져_호출자가_확인_중으로_처리하게_한다() {
    TossPaymentGateway unreachable = gatewayFor("http://127.0.0.1:1/");

    assertThatThrownBy(
            () ->
                unreachable.charge(
                    new ChargeCommand("bk", "ck", "order-000001", 100, "상품", "idem-4")))
        .isInstanceOf(TossGatewayException.class);
  }

  @Test
  void 결제_취소는_사유와_금액과_멱등키를_보낸다() {
    respond(200, "{\"paymentKey\":\"pk_1\",\"status\":\"CANCELED\"}");

    PaymentResult result = gateway.cancel(new CancelCommand("pk_1", "고객 요청", 10_000L, "idem-5"));

    assertThat(result.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(last().path()).isEqualTo("/v1/payments/pk_1/cancel");
    assertThat(last().idempotencyKey()).isEqualTo("idem-5");
    assertThat(last().body()).contains("\"cancelReason\":\"고객 요청\"").contains("10000");
  }

  @Test
  void 전액_취소는_취소_금액을_보내지_않는다() {
    respond(200, "{\"paymentKey\":\"pk_1\",\"status\":\"CANCELED\"}");

    gateway.cancel("pk_1", "중복 결제");

    assertThat(last().body()).doesNotContain("cancelAmount");
  }

  @Test
  void 이미_취소된_결제는_실패로_돌려준다() {
    respond(400, "{\"code\":\"ALREADY_CANCELED_PAYMENT\"}");

    PaymentResult result = gateway.cancel(new CancelCommand("pk_1", "사유", null, "idem-6"));

    assertThat(result)
        .isEqualTo(new PaymentResult(PaymentStatus.FAILED, "pk_1", "ALREADY_CANCELED_PAYMENT"));
  }

  @Test
  void 주문_조회는_상태를_결제_결과로_바꾼다() {
    respond(200, "{\"paymentKey\":\"pk_1\",\"status\":\"DONE\"}");
    assertThat(gateway.inquire("order-000001").status()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(last().method()).isEqualTo("GET");
    assertThat(last().path()).isEqualTo("/v1/payments/orders/order-000001");

    respond(200, "{\"paymentKey\":\"pk_1\",\"status\":\"ABORTED\"}");
    assertThat(gateway.inquire("order-000001").status()).isEqualTo(PaymentStatus.FAILED);

    respond(200, "{\"paymentKey\":\"pk_1\",\"status\":\"IN_PROGRESS\"}");
    assertThat(gateway.inquire("order-000001").status()).isEqualTo(PaymentStatus.VERIFYING);

    respond(404, "{\"code\":\"NOT_FOUND_PAYMENT\"}");
    assertThat(gateway.inquire("order-000001"))
        .isEqualTo(new PaymentResult(PaymentStatus.FAILED, null, "NOT_FOUND"));
  }

  @Test
  void 빌링키_삭제는_삭제_요청을_보내고_이미_없는_키도_성공으로_본다() {
    respond(200, "{}");
    gateway.deleteBillingKey("bk_1");
    assertThat(last().method()).isEqualTo("DELETE");
    assertThat(last().path()).isEqualTo("/v1/billing/bk_1");

    respond(404, "{\"code\":\"NOT_FOUND\"}");
    gateway.deleteBillingKey("bk_1");

    respond(500, "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\"}");
    assertThatThrownBy(() -> gateway.deleteBillingKey("bk_1"))
        .isInstanceOf(TossGatewayException.class);
  }
}
