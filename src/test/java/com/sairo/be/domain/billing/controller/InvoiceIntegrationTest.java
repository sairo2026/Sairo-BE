package com.sairo.be.domain.billing.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class InvoiceIntegrationTest extends BillingIntegrationTestBase {

  private static final String LIST_URL = "/api/billing/invoices";

  private long subscriptionId;
  private long consentId;
  private TestData.Staff owner;

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

  private long invoice(String status, long amount, String periodStart, String periodEnd) {
    return fixtures
        .invoice(owner.office().getId(), subscriptionId, consentId)
        .status(status)
        .amount(amount)
        .period(Instant.parse(periodStart), Instant.parse(periodEnd))
        .insert();
  }

  private void completedRefund(long invoiceId, String reason, long amount) {
    jdbcTemplate.update(
        "INSERT INTO refund_request (office_id, invoice_id, reason_code, reason_event_type,"
            + " reason_event_id, status, refund_amount, pg_idempotency_key) VALUES (?, ?, ?,"
            + " 'INVOICE', ?, 'COMPLETED', ?, ?)",
        owner.office().getId(),
        invoiceId,
        reason,
        invoiceId,
        amount,
        java.util.UUID.randomUUID().toString());
  }

  @Test
  void 청구서_목록은_최근_청구_기간_순이고_환불된_금액과_환불_가능_금액을_준다() throws Exception {
    long older =
        invoice("PARTIALLY_REFUNDED", 39_900, "2027-06-01T00:00:00Z", "2027-07-01T00:00:00Z");
    long newer = invoice("PAID", 49_900, "2027-07-01T00:00:00Z", "2027-08-01T00:00:00Z");
    completedRefund(older, "DUPLICATE_CHARGE", 10_000);
    mockMvc
        .perform(getRequest(LIST_URL).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].invoiceId").value(newer))
        .andExpect(jsonPath("$.items[0].amount").value(49_900))
        .andExpect(jsonPath("$.items[0].memberCount").value(1))
        .andExpect(jsonPath("$.items[0].consentAmountLimit").value(49_900))
        .andExpect(jsonPath("$.items[0].status").value("PAID"))
        .andExpect(jsonPath("$.items[0].refundedAmount").value(0))
        .andExpect(jsonPath("$.items[0].refundableAmount").value(49_900))
        .andExpect(jsonPath("$.items[0].periodStart").exists())
        .andExpect(jsonPath("$.items[0].periodEnd").exists())
        .andExpect(jsonPath("$.items[1].invoiceId").value(older))
        .andExpect(jsonPath("$.items[1].status").value("PARTIALLY_REFUNDED"))
        .andExpect(jsonPath("$.items[1].refundedAmount").value(10_000))
        .andExpect(jsonPath("$.items[1].refundableAmount").value(29_900));
  }

  @Test
  void 실패_취소_전액_환불_청구서의_환불_가능_금액은_0이다() throws Exception {
    invoice("FAILED", 39_900, "2027-05-01T00:00:00Z", "2027-06-01T00:00:00Z");
    invoice("CANCELLED", 39_900, "2027-06-01T00:00:00Z", "2027-07-01T00:00:00Z");
    invoice("FULLY_REFUNDED", 39_900, "2027-07-01T00:00:00Z", "2027-08-01T00:00:00Z");
    mockMvc
        .perform(getRequest(LIST_URL).apply(cookieOf(owner)))
        .andExpect(jsonPath("$.items[0].refundableAmount").value(0))
        .andExpect(jsonPath("$.items[1].refundableAmount").value(0))
        .andExpect(jsonPath("$.items[2].refundableAmount").value(0));
  }

  @Test
  void 청구서_목록은_다른_사무소_청구서를_포함하지_않고_페이지_크기를_지킨다() throws Exception {
    TestData.Staff other = owner();
    long otherSubscription =
        fixtures.subscription(other.office().getId()).status("ACTIVE").insert();
    long otherConsent =
        fixtures.consent(
            otherSubscription, other.user().getId(), other.office().getId(), 39_900, 3_627, 39_900);
    fixtures.invoice(other.office().getId(), otherSubscription, otherConsent).insert();
    for (int month = 1; month <= 3; month++) {
      invoice(
          "PAID",
          39_900,
          "2027-0" + month + "-01T00:00:00Z",
          "2027-0" + (month + 1) + "-01T00:00:00Z");
    }
    mockMvc
        .perform(getRequest(LIST_URL + "?size=25&page=0").apply(cookieOf(owner)))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.items.length()").value(3));
    mockMvc
        .perform(getRequest(LIST_URL + "?size=50&page=1").apply(cookieOf(owner)))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void 청구서_목록의_page와_size가_잘못되면_400이다() throws Exception {
    assertFieldError(
        mockMvc.perform(getRequest(LIST_URL + "?size=30").apply(cookieOf(owner))),
        "size",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(getRequest(LIST_URL + "?page=-1").apply(cookieOf(owner))),
        "page",
        "OUT_OF_RANGE");
    assertError(
        mockMvc.perform(getRequest(LIST_URL + "?page=abc").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 청구서_상세는_결제_시도_이력과_세액_공급자_증빙_상태를_준다() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO company_service_info (version_no, business_name, representative_name,"
            + " business_number, address, contact_phone, support_email, support_hours,"
            + " emergency_contact, privacy_officer, privacy_contact, privacy_request_path,"
            + " cancellation_path) VALUES (3, '사이로', '김대표', '7041602872', '서울', '02-0000-0000',"
            + " 'help@example.com', '평일', '긴급', '책임자', '연락처', '/privacy', '/cancel')");
    long invoiceId =
        fixtures
            .invoice(owner.office().getId(), subscriptionId, consentId)
            .status("FAILED")
            .supplierVersion(3)
            .insert();
    jdbcTemplate.update(
        "INSERT INTO billing_charge_attempt (invoice_id, attempt_no, result, failure_category,"
            + " pg_order_id, idempotency_key, attempted_at) VALUES (?, 1, 'FAILED',"
            + " 'INSUFFICIENT_FUNDS', 'ORD-20270701-01', ?, ?)",
        invoiceId,
        java.util.UUID.randomUUID().toString(),
        Timestamp.from(Instant.parse("2027-07-01T00:00:05Z")));
    jdbcTemplate.update(
        "INSERT INTO billing_charge_attempt (invoice_id, attempt_no, result, pg_transaction_id,"
            + " pg_order_id, idempotency_key, attempted_at) VALUES (?, 2, 'SUCCESS', 'toss_abc',"
            + " 'ORD-20270701-02', ?, ?)",
        invoiceId,
        java.util.UUID.randomUUID().toString(),
        Timestamp.from(Instant.parse("2027-07-01T01:00:05Z")));
    long recipientId =
        jdbcTemplate.queryForObject(
            "INSERT INTO tax_invoice_recipient (office_id, business_number, company_name,"
                + " representative_name, receiver_email, registered_by_membership_id) VALUES (?,"
                + " '1234567890', '상호', '대표', 'office@example.com', ?) RETURNING id",
            Long.class,
            owner.office().getId(),
            owner.membership().getId());
    long operatorId = fixtures.creatorOperator();
    jdbcTemplate.update(
        "INSERT INTO tax_document (billing_invoice_id, recipient_id, document_kind, status,"
            + " approval_number, issued_on, supply_amount, vat_amount, recorded_by_operator_id)"
            + " VALUES (?, ?, 'TAX_INVOICE', 'ISSUED', 'APPROVAL-1', DATE '2027-08-05', 36273,"
            + " 3627, ?)",
        invoiceId,
        recipientId,
        operatorId);
    mockMvc
        .perform(getRequest(LIST_URL + "/" + invoiceId).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.invoiceId").value(invoiceId))
        .andExpect(jsonPath("$.amount").value(39_900))
        .andExpect(jsonPath("$.supplyAmount").value(36_273))
        .andExpect(jsonPath("$.vatAmount").value(3_627))
        .andExpect(jsonPath("$.vatRateBp").value(1_000))
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.nextRetryAt").value(nullValue()))
        .andExpect(jsonPath("$.attempts.length()").value(2))
        .andExpect(jsonPath("$.attempts[0].pgOrderId").value("ORD-20270701-01"))
        .andExpect(jsonPath("$.attempts[0].result").value("FAILED"))
        .andExpect(jsonPath("$.attempts[0].failureCategory").value("INSUFFICIENT_FUNDS"))
        .andExpect(jsonPath("$.attempts[0].pgTransactionId").value(nullValue()))
        .andExpect(jsonPath("$.attempts[1].pgTransactionId").value("toss_abc"))
        .andExpect(jsonPath("$.attempts[1].failureCategory").value(nullValue()))
        .andExpect(jsonPath("$.supplier.businessName").value("사이로"))
        .andExpect(jsonPath("$.supplier.infoVersion").value(3))
        .andExpect(jsonPath("$.taxDocuments.length()").value(1))
        .andExpect(jsonPath("$.taxDocuments[0].documentKind").value("TAX_INVOICE"))
        .andExpect(jsonPath("$.taxDocuments[0].status").value("ISSUED"))
        .andExpect(jsonPath("$.taxDocuments[0].issuedOn").value("2027-08-05"))
        .andExpect(jsonPath("$.taxDocuments[0].maskedReceiverEmail").value("of***@example.com"))
        .andExpect(jsonPath("$.taxDocuments[0].supplyAmount").value(36_273));
  }

  @Test
  void 공급자_버전과_증빙이_없는_청구서_상세도_조회된다() throws Exception {
    long invoiceId = invoice("PAID", 39_900, "2027-07-01T00:00:00Z", "2027-08-01T00:00:00Z");
    mockMvc
        .perform(getRequest(LIST_URL + "/" + invoiceId).apply(cookieOf(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attempts.length()").value(0))
        .andExpect(jsonPath("$.supplier").value(nullValue()))
        .andExpect(jsonPath("$.taxDocuments.length()").value(0))
        .andExpect(jsonPath("$.refundableAmount").value(39_900));
  }

  @Test
  void 청구서_상세는_없거나_다른_사무소_청구서이면_404이고_경로_값이_숫자가_아니면_400이다() throws Exception {
    TestData.Staff other = owner();
    long otherSubscription =
        fixtures.subscription(other.office().getId()).status("ACTIVE").insert();
    long otherConsent =
        fixtures.consent(
            otherSubscription, other.user().getId(), other.office().getId(), 39_900, 3_627, 39_900);
    long otherInvoice =
        fixtures.invoice(other.office().getId(), otherSubscription, otherConsent).insert();
    assertError(
        mockMvc.perform(getRequest(LIST_URL + "/" + otherInvoice).apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(getRequest(LIST_URL + "/999999").apply(cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(getRequest(LIST_URL + "/abc").apply(cookieOf(owner))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 청구서_목록과_상세는_대표만_조회할_수_있다() throws Exception {
    assertOwnerOnly(getRequest(LIST_URL));
    assertOwnerOnly(getRequest(LIST_URL + "/1"));
  }
}
