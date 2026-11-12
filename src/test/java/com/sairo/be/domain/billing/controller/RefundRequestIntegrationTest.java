package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class RefundRequestIntegrationTest extends BillingIntegrationTestBase {

  private static final String URL = "/api/refund-requests";

  private TestData.Staff owner;
  private long subscriptionId;
  private long consentId;

  @BeforeEach
  void seed() {
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    owner = owner();
    subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(Instant.parse("2027-07-01T00:00:00Z"), Instant.parse("2027-07-31T00:00:00Z"))
            .insert();
    consentId =
        fixtures.consent(
            subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
  }

  private int invoiceSequence = 0;

  private long invoice(String status, long amount) {
    Instant start =
        Instant.parse("2027-01-01T00:00:00Z").plusSeconds(86_400L * 30 * invoiceSequence++);
    return fixtures
        .invoice(owner.office().getId(), subscriptionId, consentId)
        .status(status)
        .amount(amount)
        .period(start, start.plusSeconds(86_400L * 30))
        .insert();
  }

  private static String body(long invoiceId, String reason) {
    return "{\"invoiceId\":" + invoiceId + ",\"reasonCode\":\"" + reason + "\"}";
  }

  private void insertRefund(long invoiceId, String reason, String status, long amount) {
    boolean needsKey = !"RECEIVED".equals(status) && !"REJECTED".equals(status);
    boolean invoiceEvent = "DUPLICATE_CHARGE".equals(reason) || "INCORRECT_CHARGE".equals(reason);
    String eventType =
        switch (reason) {
          case "DUPLICATE_CHARGE", "INCORRECT_CHARGE" -> "INVOICE";
          case "CANCELLATION" -> "SUBSCRIPTION_CANCELLATION";
          case "SERVICE_FAILURE" -> "SERVICE_INCIDENT";
          default -> reason;
        };
    jdbcTemplate.update(
        "INSERT INTO refund_request (office_id, invoice_id, reason_code, reason_event_type,"
            + " reason_event_id, status, refund_amount, pg_idempotency_key) VALUES (?, ?, ?,"
            + " ?, ?, ?, ?, ?)",
        owner.office().getId(),
        invoiceId,
        reason,
        eventType,
        invoiceEvent ? invoiceId : null,
        status,
        amount,
        needsKey ? UUID.randomUUID().toString() : null);
  }

  @Test
  void 중복결제_환불_신청은_201과_접수_상태를_주고_권리액_계산을_고정한다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    long refundId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE"))
                            .apply(cookieOf(owner)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("RECEIVED"))
                    .andReturn())
            .get("refundId")
            .asLong();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT reason_event_type, reason_event_id, entitlement_amount, previous_refund_amount,"
                + " reserved_amount, refund_amount, original_approved_amount, total_period_seconds,"
                + " coupon_excluded_amount, prior_compensation_amount FROM refund_request WHERE id = ?",
            refundId);
    assertThat(row)
        .containsEntry("reason_event_type", "INVOICE")
        .containsEntry("reason_event_id", invoiceId)
        .containsEntry("entitlement_amount", 39_900L)
        .containsEntry("previous_refund_amount", 0L)
        .containsEntry("reserved_amount", 0L)
        .containsEntry("refund_amount", 39_900L)
        .containsEntry("original_approved_amount", 39_900L)
        .containsEntry("total_period_seconds", 30L * 86_400L)
        .containsEntry("coupon_excluded_amount", 0L)
        .containsEntry("prior_compensation_amount", 0L);
  }

  @Test
  void 오청구_환불_신청도_청구서를_사건으로_삼아_접수한다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    mockMvc
        .perform(postRequest(URL, body(invoiceId, "INCORRECT_CHARGE")).apply(cookieOf(owner)))
        .andExpect(status().isCreated());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reason_event_id FROM refund_request WHERE reason_code = 'INCORRECT_CHARGE'",
                Long.class))
        .isEqualTo(invoiceId);
  }

  @Test
  void 해지_인증중단_서비스장애_승인지연_정책보상은_사건_ID_없이_접수하고_계산은_운영자_검토로_넘긴다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    for (String reason :
        new String[] {
          "CANCELLATION",
          "AUTH_SUSPENSION",
          "SERVICE_FAILURE",
          "LATE_APPROVAL",
          "POLICY_COMPENSATION"
        }) {
      mockMvc
          .perform(postRequest(URL, body(invoiceId, reason)).apply(cookieOf(owner)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("RECEIVED"));
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refund_request WHERE invoice_id = ? AND reason_event_id IS NULL"
                    + " AND entitlement_amount IS NULL AND refund_amount IS NULL",
                Integer.class,
                invoiceId))
        .isEqualTo(5);
    Map<String, Object> types =
        jdbcTemplate.queryForMap(
            "SELECT reason_event_type FROM refund_request WHERE reason_code = 'SERVICE_FAILURE'");
    assertThat(types).containsEntry("reason_event_type", "SERVICE_INCIDENT");
  }

  @Test
  void 같은_청구서_같은_사유의_재요청은_새_신청_없이_기존_신청_번호와_상태를_준다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    long first =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE"))
                            .apply(cookieOf(owner)))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("refundId")
            .asLong();
    mockMvc
        .perform(postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE")).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundId").value(first))
        .andExpect(jsonPath("$.status").value("RECEIVED"));
    mockMvc
        .perform(postRequest(URL, body(invoiceId, "CANCELLATION")).apply(cookieOf(owner)))
        .andExpect(status().isCreated());
    mockMvc
        .perform(postRequest(URL, body(invoiceId, "CANCELLATION")).apply(cookieOf(owner)))
        .andExpect(status().isOk());
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refund_request", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void 중복결제와_오청구는_같은_청구서_사건이므로_어느_쪽으로_재요청해도_기존_신청을_준다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    long first =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE"))
                            .apply(cookieOf(owner)))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("refundId")
            .asLong();
    mockMvc
        .perform(postRequest(URL, body(invoiceId, "INCORRECT_CHARGE")).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundId").value(first));
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refund_request", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void 환불_가능_금액은_완료된_환불과_처리_중_예약액을_뺀_값이다() throws Exception {
    long invoiceId = invoice("PARTIALLY_REFUNDED", 39_900);
    insertRefund(invoiceId, "SERVICE_FAILURE", "COMPLETED", 10_000);
    insertRefund(invoiceId, "LATE_APPROVAL", "PROCESSING", 5_000);
    mockMvc
        .perform(postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE")).apply(cookieOf(owner)))
        .andExpect(status().isCreated());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT refund_amount, reserved_amount, previous_refund_amount FROM refund_request"
                + " WHERE reason_code = 'DUPLICATE_CHARGE'");
    assertThat(row)
        .containsEntry("refund_amount", 24_900L)
        .containsEntry("reserved_amount", 5_000L)
        .containsEntry("previous_refund_amount", 0L);
  }

  @Test
  void 결제_성공이_아니거나_이미_전액_환불된_청구서는_409_INVALID_TRANSITION이다() throws Exception {
    for (String state :
        new String[] {
          "FAILED", "UNBILLED", "PROCESSING", "VERIFYING", "CANCELLED", "FULLY_REFUNDED"
        }) {
      long invoiceId = invoice(state, 39_900);
      assertError(
          mockMvc.perform(
              postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE")).apply(cookieOf(owner))),
          ErrorCode.INVALID_TRANSITION);
    }
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refund_request", Integer.class))
        .isZero();
  }

  @Test
  void 환불_가능_금액이_0이면_409_INVALID_TRANSITION이다() throws Exception {
    long invoiceId = invoice("PARTIALLY_REFUNDED", 39_900);
    insertRefund(invoiceId, "SERVICE_FAILURE", "COMPLETED", 39_900);
    assertError(
        mockMvc.perform(
            postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE")).apply(cookieOf(owner))),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 없는_청구서와_다른_사무소_청구서는_404_RESOURCE_NOT_FOUND이다() throws Exception {
    TestData.Staff other = owner();
    long otherSubscription =
        fixtures.subscription(other.office().getId()).status("ACTIVE").insert();
    long otherConsent =
        fixtures.consent(
            otherSubscription, other.user().getId(), other.office().getId(), 39_900, 3_627, 39_900);
    long otherInvoice =
        fixtures.invoice(other.office().getId(), otherSubscription, otherConsent).insert();
    assertError(
        mockMvc.perform(
            postRequest(URL, body(otherInvoice, "DUPLICATE_CHARGE")).apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(postRequest(URL, body(999_999, "DUPLICATE_CHARGE")).apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 필수_필드가_없거나_사유_분류가_허용값이_아니면_400이다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    assertError(
        mockMvc.perform(
            postRequest(URL, "{\"reasonCode\":\"DUPLICATE_CHARGE\"}").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            postRequest(URL, "{\"invoiceId\":" + invoiceId + "}").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(postRequest(URL, body(invoiceId, "NO_SUCH_REASON")).apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 사유_분류와_맞지_않는_사건_정보를_보내면_400이다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    assertFieldError(
        mockMvc.perform(
            postRequest(
                    URL,
                    "{\"invoiceId\":"
                        + invoiceId
                        + ",\"reasonCode\":\"DUPLICATE_CHARGE\",\"reasonEventType\":\"AUTH_SUSPENSION\"}")
                .apply(cookieOf(owner))),
        "reasonEventType",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            postRequest(
                    URL,
                    "{\"invoiceId\":"
                        + invoiceId
                        + ",\"reasonCode\":\"CANCELLATION\",\"reasonEventId\":77}")
                .apply(cookieOf(owner))),
        "reasonEventId",
        "NOT_ALLOWED");
    mockMvc
        .perform(
            postRequest(
                    URL,
                    "{\"invoiceId\":"
                        + invoiceId
                        + ",\"reasonCode\":\"DUPLICATE_CHARGE\",\"reasonEventType\":\"INVOICE\","
                        + "\"reasonEventId\":"
                        + invoiceId
                        + "}")
                .apply(cookieOf(owner)))
        .andExpect(status().isCreated());
  }

  @Test
  void 환불_처리_상태_조회는_계산_근거를_주고_계산_전에는_calculation이_null이다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    long calculated =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        postRequest(URL, body(invoiceId, "DUPLICATE_CHARGE"))
                            .apply(cookieOf(owner)))
                    .andReturn())
            .get("refundId")
            .asLong();
    long pending =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        postRequest(URL, body(invoiceId, "CANCELLATION")).apply(cookieOf(owner)))
                    .andReturn())
            .get("refundId")
            .asLong();
    mockMvc
        .perform(getRequest(URL + "/" + calculated).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.refundId").value(calculated))
        .andExpect(jsonPath("$.invoiceId").value(invoiceId))
        .andExpect(jsonPath("$.status").value("RECEIVED"))
        .andExpect(jsonPath("$.reasonCode").value("DUPLICATE_CHARGE"))
        .andExpect(jsonPath("$.entitlementAmount").value(39_900))
        .andExpect(jsonPath("$.previousRefundAmount").value(0))
        .andExpect(jsonPath("$.reservedAmount").value(0))
        .andExpect(jsonPath("$.refundAmount").value(39_900))
        .andExpect(jsonPath("$.rejectionReason").value(nullValue()))
        .andExpect(jsonPath("$.failureReason").value(nullValue()))
        .andExpect(jsonPath("$.calculation.originalApprovedAmount").value(39_900))
        .andExpect(jsonPath("$.calculation.totalPeriodSeconds").value(30 * 86_400))
        .andExpect(jsonPath("$.calculation.remainingPeriodSeconds").value(nullValue()))
        .andExpect(jsonPath("$.calculation.couponExcludedAmount").value(0))
        .andExpect(jsonPath("$.calculation.priorCompensationAmount").value(0))
        .andExpect(jsonPath("$.calculation.effectiveEndAt").value(nullValue()))
        .andExpect(jsonPath("$.createdAt").isString());
    mockMvc
        .perform(getRequest(URL + "/" + pending).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entitlementAmount").value(nullValue()))
        .andExpect(jsonPath("$.refundAmount").value(nullValue()))
        .andExpect(jsonPath("$.calculation").value(nullValue()));
  }

  @Test
  void 거절과_실패_사유를_상태_조회에_노출한다() throws Exception {
    long invoiceId = invoice("PAID", 39_900);
    insertRefund(invoiceId, "DUPLICATE_CHARGE", "REJECTED", 0);
    jdbcTemplate.update(
        "UPDATE refund_request SET rejection_reason = '중복 결제가 아님' WHERE invoice_id = ?", invoiceId);
    long failedInvoice =
        fixtures
            .invoice(owner.office().getId(), subscriptionId, consentId)
            .period(Instant.parse("2027-08-01T00:00:00Z"), Instant.parse("2027-09-01T00:00:00Z"))
            .insert();
    insertRefund(failedInvoice, "INCORRECT_CHARGE", "FAILED", 100);
    jdbcTemplate.update(
        "UPDATE refund_request SET failure_reason = 'PG 취소 실패' WHERE reason_code = 'INCORRECT_CHARGE'");
    long rejected =
        jdbcTemplate.queryForObject(
            "SELECT id FROM refund_request WHERE reason_code = 'DUPLICATE_CHARGE'", Long.class);
    long failed =
        jdbcTemplate.queryForObject(
            "SELECT id FROM refund_request WHERE reason_code = 'INCORRECT_CHARGE'", Long.class);
    mockMvc
        .perform(getRequest(URL + "/" + rejected).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("중복 결제가 아님"));
    mockMvc
        .perform(getRequest(URL + "/" + failed).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("PG 취소 실패"));
  }

  @Test
  void 환불_상태_조회는_없거나_다른_사무소_신청이면_404이고_경로_값이_숫자가_아니면_400이다() throws Exception {
    TestData.Staff other = owner();
    long otherSubscription =
        fixtures.subscription(other.office().getId()).status("ACTIVE").insert();
    long otherConsent =
        fixtures.consent(
            otherSubscription, other.user().getId(), other.office().getId(), 39_900, 3_627, 39_900);
    long otherInvoice =
        fixtures.invoice(other.office().getId(), otherSubscription, otherConsent).insert();
    jdbcTemplate.update(
        "INSERT INTO refund_request (office_id, invoice_id, reason_code, reason_event_type,"
            + " reason_event_id, status) VALUES (?, ?, 'DUPLICATE_CHARGE', 'INVOICE', ?,"
            + " 'RECEIVED')",
        other.office().getId(),
        otherInvoice,
        otherInvoice);
    long otherRefund = jdbcTemplate.queryForObject("SELECT id FROM refund_request", Long.class);
    assertError(
        mockMvc.perform(getRequest(URL + "/" + otherRefund).apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(getRequest(URL + "/999999").apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(getRequest(URL + "/abc").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 환불_목록은_최근_신청부터_청구서와_상태로_거르고_자기_사무소만_준다() throws Exception {
    long first = invoice("PAID", 39_900);
    long second =
        fixtures
            .invoice(owner.office().getId(), subscriptionId, consentId)
            .period(Instant.parse("2027-08-01T00:00:00Z"), Instant.parse("2027-09-01T00:00:00Z"))
            .insert();
    mockMvc
        .perform(postRequest(URL, body(first, "DUPLICATE_CHARGE")).apply(cookieOf(owner)))
        .andExpect(status().isCreated());
    mockMvc
        .perform(postRequest(URL, body(second, "CANCELLATION")).apply(cookieOf(owner)))
        .andExpect(status().isCreated());
    insertRefund(first, "SERVICE_FAILURE", "REJECTED", 0);
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].refundId").exists())
        .andExpect(jsonPath("$.items[0].createdAt").exists());
    mockMvc
        .perform(getRequest(URL + "?invoiceId=" + second).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].invoiceId").value(second))
        .andExpect(jsonPath("$.items[0].reasonCode").value("CANCELLATION"))
        .andExpect(jsonPath("$.items[0].refundAmount").value(nullValue()));
    mockMvc
        .perform(getRequest(URL + "?status=REJECTED,COMPLETED").apply(cookieOf(owner)))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].status").value("REJECTED"));
    TestData.Staff other = owner();
    mockMvc
        .perform(getRequest(URL).apply(cookieOf(other)))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 환불_목록의_status_page_size가_잘못되면_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(getRequest(URL + "?status=NOPE").apply(cookieOf(owner))),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(getRequest(URL + "?size=10").apply(cookieOf(owner))),
        "size",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(getRequest(URL + "?invoiceId=abc").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 환불_신청_목록_상세는_대표만_할_수_있다() throws Exception {
    assertOwnerOnly(postRequest(URL, "{\"invoiceId\":1,\"reasonCode\":\"DUPLICATE_CHARGE\"}"));
    assertOwnerOnly(getRequest(URL));
    assertOwnerOnly(getRequest(URL + "/1"));
  }
}
