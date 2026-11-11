package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.service.BillingReconciliationService;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway.CancelCommand;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class OpsBillingIntegrationTest extends OpsConsoleTestBase {

  private static final String INVOICES = "/api/ops/billing/invoices";
  private static final String REFUNDS = "/api/ops/refund-requests";
  private static final String RECON = "/api/ops/billing/reconciliation";
  private static final String TAX = "/api/ops/billing/tax-documents";
  private static final LocalDate BUSINESS_DATE = LocalDate.of(2027, 1, 5);

  @Autowired private StubPaymentGateway paymentGateway;
  @Autowired private BillingReconciliationService reconciliationService;

  private BillingFixtures fixtures;
  private Operator billing;
  private Cookie billingSession;
  private Operator billing2;
  private Cookie billing2Session;
  private TestData.Staff owner;
  private long subscriptionId;
  private long consentId;
  private int invoiceSequence;

  @BeforeEach
  void setUp() {
    paymentGateway.reset();
    fixtures = new BillingFixtures(jdbcTemplate);
    billing = operator("박결제", OperatorRole.BILLING);
    billingSession = sessionOf(billing);
    billing2 = operator("이결제", OperatorRole.BILLING);
    billing2Session = sessionOf(billing2);
    owner = staff(OfficeMembershipRole.OWNER);
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    Instant now = Instant.now();
    subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(now.minus(Duration.ofDays(10)), now.plus(Duration.ofDays(20)))
            .insert();
    consentId =
        fixtures.consent(
            subscriptionId, owner.user().getId(), owner.office().getId(), 33_000, 3_000, 33_000);
  }

  private long invoice(String status, long amount) {
    Instant now = Instant.now();
    invoiceSequence++;
    return fixtures
        .invoice(owner.office().getId(), subscriptionId, consentId)
        .amount(amount)
        .status(status)
        .period(
            now.minus(Duration.ofDays(10)).plusSeconds(invoiceSequence),
            now.plus(Duration.ofDays(20)))
        .insert();
  }

  private void attempt(long invoiceId, int no, String result, String orderId, Instant resolvedAt) {
    jdbcTemplate.update(
        "INSERT INTO billing_charge_attempt (invoice_id, attempt_no, result, pg_transaction_id,"
            + " pg_order_id, idempotency_key, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        invoiceId,
        no,
        result,
        "SUCCESS".equals(result) ? "paykey-" + orderId : null,
        orderId,
        UUID.randomUUID().toString(),
        resolvedAt == null ? null : Timestamp.from(resolvedAt));
  }

  private long refund(long invoiceId, String reason, String eventType, Long eventId) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO refund_request (office_id, invoice_id, reason_code, reason_event_type,"
            + " reason_event_id, status) VALUES (?, ?, ?, ?, ?, 'RECEIVED') RETURNING id",
        Long.class,
        owner.office().getId(),
        invoiceId,
        reason,
        eventType,
        eventId);
  }

  private long entry(String status, long invoiceId) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO billing_reconciliation_entry (business_date, source_type, pg_order_id,"
            + " invoice_id, office_id, internal_amount, pg_amount, status) VALUES (?, 'PG_QUERY',"
            + " 'ORDER-X1', ?, ?, 33000, 30000, ?) RETURNING id",
        Long.class,
        BUSINESS_DATE,
        invoiceId,
        owner.office().getId(),
        status);
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_결제담당이_아니면_403이다() throws Exception {
    Cookie support = sessionOf(operator("김지원", OperatorRole.SUPPORT));
    for (String path :
        new String[] {INVOICES, INVOICES + "/1", REFUNDS, REFUNDS + "/1", RECON, TAX}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(support)), ErrorCode.FORBIDDEN);
    }
    for (String path :
        new String[] {
          REFUNDS + "/1/review",
          REFUNDS + "/1/execute",
          RECON + "/1/resolve",
          INVOICES + "/1/tax-documents",
          TAX + "/1/amend"
        }) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, "{}", support), ErrorCode.FORBIDDEN);
    }
    assertError(
        mockMvc.perform(get(INVOICES).cookie(memberSession(owner))), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 청구서_목록과_상세는_결제_시도와_연결_환불을_보이고_결제키는_담지_않는다() throws Exception {
    long paid = invoice("PAID", 33_000);
    long failed = invoice("FAILED", 33_000);
    attempt(paid, 1, "FAILED", "ORDER-A1", Instant.now());
    attempt(paid, 2, "SUCCESS", "ORDER-A2", Instant.now());
    refund(paid, "DUPLICATE_CHARGE", "INVOICE", paid);

    mockMvc
        .perform(get(INVOICES).cookie(billingSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mockMvc
        .perform(get(INVOICES).param("status", "FAILED").cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].invoiceId").value(failed));
    mockMvc
        .perform(
            get(INVOICES)
                .param("officeId", String.valueOf(owner.office().getId()))
                .param("dueFrom", "2000-01-01")
                .param("dueTo", "2099-12-31")
                .cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(2));
    assertFieldError(
        mockMvc.perform(get(INVOICES).param("status", "NOPE").cookie(billingSession)),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(get(INVOICES).param("dueFrom", "2027/01/01").cookie(billingSession)),
        "dueFrom",
        "INVALID_FORMAT");

    String body =
        mockMvc
            .perform(get(INVOICES + "/" + paid).cookie(billingSession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.chargeAttempts.length()").value(2))
            .andExpect(jsonPath("$.chargeAttempts[0].pgOrderId").value("ORDER-A1"))
            .andExpect(jsonPath("$.chargeAttempts[1].result").value("SUCCESS"))
            .andExpect(jsonPath("$.chargeAttempts[1].failureReason").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.refunds[0].reasonCode").value("DUPLICATE_CHARGE"))
            .andExpect(jsonPath("$.purchaseConsentId").value(consentId))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("paykey-");
    assertError(
        mockMvc.perform(get(INVOICES + "/999999").cookie(billingSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 환불_검토는_사건_ID를_채우고_권리액을_고정하며_거절은_사유가_필요하다() throws Exception {
    long invoiceId = invoice("PAID", 30_000);
    long cancellation = refund(invoiceId, "CANCELLATION", "SUBSCRIPTION_CANCELLATION", null);
    long duplicate = refund(invoiceId, "DUPLICATE_CHARGE", "INVOICE", invoiceId);

    mockMvc
        .perform(get(REFUNDS).param("status", "RECEIVED").cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].refundId").value(cancellation));
    assertFieldError(
        post(
            REFUNDS + "/" + cancellation + "/review", "{\"decision\":\"APPROVE\"}", billingSession),
        "reasonEventId",
        "REQUIRED");
    assertFieldError(
        post(REFUNDS + "/" + cancellation + "/review", "{\"decision\":\"REJECT\"}", billingSession),
        "note",
        "REQUIRED");
    assertFieldError(
        post(
            REFUNDS + "/" + cancellation + "/review",
            "{\"decision\":\"APPROVE\",\"reasonEventId\":\"abc\"}",
            billingSession),
        "reasonEventId",
        "INVALID_FORMAT");

    post(
            REFUNDS + "/" + cancellation + "/review",
            "{\"decision\":\"APPROVE\",\"reasonEventId\":\"77\",\"note\":\"해지 확인\"}",
            billingSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.entitlementAmount").value(Matchers.lessThanOrEqualTo(20_000)))
        .andExpect(jsonPath("$.entitlementAmount").value(Matchers.greaterThan(19_000)))
        .andExpect(jsonPath("$.previousRefundAmount").value(0));
    mockMvc
        .perform(get(REFUNDS + "/" + cancellation).cookie(billingSession))
        .andExpect(jsonPath("$.reasonEventId").value("77"))
        .andExpect(jsonPath("$.reviewedByOperatorId").value(billing.id()))
        .andExpect(jsonPath("$.reviewedByOperatorName").value("박결제"))
        .andExpect(jsonPath("$.pgRefundId").value(Matchers.nullValue()));
    assertError(
        post(
            REFUNDS + "/" + cancellation + "/review",
            "{\"decision\":\"REJECT\",\"note\":\"다시\"}",
            billingSession),
        ErrorCode.INVALID_TRANSITION);

    long reserved = count("SELECT refund_amount FROM refund_request WHERE id = ?", cancellation);
    post(REFUNDS + "/" + duplicate + "/review", "{\"decision\":\"APPROVE\"}", billingSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reservedAmount").value(reserved))
        .andExpect(jsonPath("$.refundAmount").value(30_000 - reserved));
    long other = refund(invoiceId, "SERVICE_FAILURE", "SERVICE_INCIDENT", null);
    post(
            REFUNDS + "/" + other + "/review",
            "{\"decision\":\"REJECT\",\"note\":\"장애 아님\"}",
            billingSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("장애 아님"));
    assertThat(auditCount("REFUND_APPROVED")).isEqualTo(2);
    assertThat(auditCount("REFUND_REJECTED")).isEqualTo(1);
    assertError(
        post(REFUNDS + "/999999/review", "{\"decision\":\"APPROVE\"}", billingSession),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 취소_실행은_재인증과_같은_멱등키를_쓰고_청구서_환불_상태를_바꾼다() throws Exception {
    long invoiceId = invoice("PAID", 30_000);
    attempt(invoiceId, 1, "SUCCESS", "ORDER-B1", Instant.now());
    long refundId = refund(invoiceId, "DUPLICATE_CHARGE", "INVOICE", invoiceId);
    String execute = REFUNDS + "/" + refundId + "/execute";

    assertError(post(execute, "", billingSession), ErrorCode.STEP_UP_REQUIRED);
    stepUp(billingSession);
    assertError(post(execute, "", billingSession), ErrorCode.INVALID_TRANSITION);
    post(REFUNDS + "/" + refundId + "/review", "{\"decision\":\"APPROVE\"}", billingSession)
        .andExpect(status().isOk());

    paymentGateway.throwOnNextCancel(new IllegalStateException("timeout"));
    post(execute, "", billingSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VERIFYING"))
        .andExpect(jsonPath("$.failureReason").value(Matchers.nullValue()));
    post(execute, "", billingSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    List<CancelCommand> cancels = paymentGateway.cancels();
    assertThat(cancels).hasSize(2);
    assertThat(cancels.get(0).idempotencyKey()).isEqualTo(cancels.get(1).idempotencyKey());
    assertThat(cancels.get(0).amount()).isEqualTo(30_000L);
    assertThat(cancels.get(0).paymentKey()).isEqualTo("paykey-ORDER-B1");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("FULLY_REFUNDED");
    mockMvc
        .perform(get(REFUNDS + "/" + refundId).cookie(billingSession))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.pgRefundId").value("payk" + "*".repeat(11)))
        .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    assertError(post(execute, "", billingSession), ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("REFUND_EXECUTED")).isEqualTo(2);
  }

  @Test
  void 취소가_확정_실패하면_실패_사유를_남기고_정책_외_보상은_승인자가_실행할_수_없다() throws Exception {
    long invoiceId = invoice("PAID", 30_000);
    attempt(invoiceId, 1, "SUCCESS", "ORDER-C1", Instant.now());
    long failing = refund(invoiceId, "INCORRECT_CHARGE", "INVOICE", invoiceId);
    post(REFUNDS + "/" + failing + "/review", "{\"decision\":\"APPROVE\"}", billingSession);
    stepUp(billingSession);
    paymentGateway.respondToCancelWith(PaymentStatus.FAILED);
    post(REFUNDS + "/" + failing + "/execute", "", billingSession)
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(
            jsonPath("$.failureReason").value(Matchers.containsString("STUB_CANCEL_DECLINED")));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM billing_invoice WHERE id = ?", String.class, invoiceId))
        .isEqualTo("PAID");

    paymentGateway.respondToCancelWith(PaymentStatus.SUCCEEDED);
    long policy = refund(invoiceId, "POLICY_COMPENSATION", "POLICY_COMPENSATION", null);
    post(
            REFUNDS + "/" + policy + "/review",
            "{\"decision\":\"APPROVE\",\"reasonEventId\":\"5\"}",
            billingSession)
        .andExpect(jsonPath("$.refundAmount").value(30_000));
    assertError(
        post(REFUNDS + "/" + policy + "/execute", "", billingSession),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    stepUp(billing2Session);
    post(REFUNDS + "/" + policy + "/execute", "", billing2Session)
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    assertError(
        post(REFUNDS + "/999999/execute", "", billing2Session), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 일간_대사는_PG_조회와_비교해_항목을_한_번만_만든다() throws Exception {
    Instant resolvedAt = Instant.parse("2027-01-05T03:00:00Z");
    long matched = invoice("PAID", 33_000);
    long mismatched = invoice("PAID", 33_000);
    long internalOnly = invoice("PAID", 33_000);
    long pgOnly = invoice("FAILED", 33_000);
    attempt(matched, 1, "SUCCESS", "ORDER-M1", resolvedAt);
    attempt(mismatched, 1, "SUCCESS", "ORDER-M2", resolvedAt);
    attempt(internalOnly, 1, "SUCCESS", "ORDER-M3", resolvedAt);
    attempt(pgOnly, 1, "FAILED", "ORDER-M4", resolvedAt);
    attempt(invoice("PAID", 33_000), 1, "SUCCESS", "ORDER-M5", resolvedAt.plus(Duration.ofDays(1)));
    paymentGateway.recordApproval("ORDER-M1", "pk-1", 33_000);
    paymentGateway.recordApproval("ORDER-M2", "pk-2", 30_000);
    paymentGateway.recordApproval("ORDER-M4", "pk-4", 33_000);

    assertThat(reconciliationService.run(BUSINESS_DATE)).isTrue();
    assertThat(reconciliationService.run(BUSINESS_DATE)).isFalse();

    mockMvc
        .perform(get(RECON).param("businessDate", "2027-01-05").cookie(billingSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(4))
        .andExpect(jsonPath("$.items[0].status").value("MATCHED"))
        .andExpect(jsonPath("$.items[0].invoiceId").value(matched))
        .andExpect(jsonPath("$.items[1].status").value("AMOUNT_MISMATCH"))
        .andExpect(jsonPath("$.items[1].pgAmount").value(30_000))
        .andExpect(jsonPath("$.items[2].status").value("INTERNAL_ONLY"))
        .andExpect(jsonPath("$.items[3].status").value("PG_ONLY"))
        .andExpect(jsonPath("$.items[3].internalAmount").value(Matchers.nullValue()));
    mockMvc
        .perform(
            get(RECON)
                .param("businessDate", "2027-01-05")
                .param("unresolved", "true")
                .cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(3));
    mockMvc
        .perform(get(RECON).cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertFieldError(
        mockMvc.perform(get(RECON).param("status", "NOPE").cookie(billingSession)),
        "status",
        "NOT_ALLOWED");
  }

  @Test
  void 대사_해결은_원인과_근거를_남기고_일치하거나_해결된_항목은_409다() throws Exception {
    long invoiceId = invoice("PAID", 33_000);
    long matched = entry("MATCHED", invoiceId);
    long mismatch = entry("AMOUNT_MISMATCH", invoiceId);
    String body = "{\"differenceReason\":\"PG 수수료 차이\",\"resolutionEvidence\":\"정산서 대조\"}";

    assertError(
        post(RECON + "/" + matched + "/resolve", body, billingSession),
        ErrorCode.INVALID_TRANSITION);
    assertFieldError(
        post(RECON + "/" + mismatch + "/resolve", "{\"differenceReason\":\"원인\"}", billingSession),
        "resolutionEvidence",
        "REQUIRED");
    Operator support = operator("김지원", OperatorRole.SUPPORT);
    assertError(
        post(
            RECON + "/" + mismatch + "/resolve",
            "{\"differenceReason\":\"원인\",\"resolutionEvidence\":\"근거\",\"assigneeOperatorId\":"
                + support.id()
                + "}",
            billingSession),
        ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    post(
            RECON + "/" + mismatch + "/resolve",
            "{\"differenceReason\":\"PG 수수료 차이\",\"resolutionEvidence\":\"정산서 대조\","
                + "\"assigneeOperatorId\":"
                + billing2.id()
                + "}",
            billingSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("AMOUNT_MISMATCH"))
        .andExpect(jsonPath("$.assignedOperatorId").value(billing2.id()))
        .andExpect(jsonPath("$.resolvedAt").isNotEmpty());
    assertError(
        post(RECON + "/" + mismatch + "/resolve", body, billingSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(RECON + "/999999/resolve", body, billingSession), ErrorCode.RESOURCE_NOT_FOUND);
    mockMvc
        .perform(
            get(RECON)
                .param("businessDate", "2027-01-05")
                .param("assignedOperatorId", String.valueOf(billing2.id()))
                .cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].differenceReason").value("PG 수수료 차이"));
    assertThat(auditCount("RECONCILIATION_RESOLVED")).isEqualTo(1);
  }

  @Test
  void 증빙_기록은_청구서_금액과_대조하고_대기_기록을_발급됨으로_바꾼다() throws Exception {
    long unpaid = invoice("UNBILLED", 33_000);
    long paid = invoice("PAID", 33_000);
    String issued =
        "{\"documentKind\":\"TAX_INVOICE\",\"isElectronic\":true,\"status\":\"ISSUED\","
            + "\"approvalNumber\":\"approval-1\",\"issuedOn\":\"2027-02-05\","
            + "\"supplyAmount\":30000,\"vatAmount\":3000}";

    assertError(
        post(INVOICES + "/" + unpaid + "/tax-documents", issued, billingSession),
        ErrorCode.TAX_INVOICE_NOT_ELIGIBLE);
    assertError(
        post(INVOICES + "/999999/tax-documents", issued, billingSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        post(
            INVOICES + "/" + paid + "/tax-documents",
            "{\"documentKind\":\"TAX_INVOICE\",\"isElectronic\":true,\"status\":\"ISSUED\"}",
            billingSession),
        "approvalNumber",
        "REQUIRED");
    assertFieldError(
        post(
            INVOICES + "/" + paid + "/tax-documents",
            issued.replace("30000", "29000").replace("3000}", "4000}"),
            billingSession),
        "supplyAmount",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            INVOICES + "/" + paid + "/tax-documents",
            "{\"documentKind\":\"TAX_INVOICE\",\"isElectronic\":true,\"status\":\"ISSUE_FAILED\"}",
            billingSession),
        "operatorNote",
        "REQUIRED");

    String pending =
        post(
                INVOICES + "/" + paid + "/tax-documents",
                "{\"documentKind\":\"TAX_INVOICE\",\"isElectronic\":true,\"status\":\"PENDING\"}",
                billingSession)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.version").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long documentId = Long.parseLong(pending.replaceAll("(?s).*\"documentId\":(\\d+).*", "$1"));
    post(INVOICES + "/" + paid + "/tax-documents", issued, billingSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.documentId").value(documentId))
        .andExpect(jsonPath("$.status").value("ISSUED"))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT retention_until FROM tax_document WHERE id = ?",
                LocalDate.class,
                documentId))
        .isEqualTo(LocalDate.of(2027, 2, 5).plusDays(1825));
    assertError(
        post(INVOICES + "/" + paid + "/tax-documents", issued, billingSession),
        ErrorCode.TAX_DOCUMENT_ALREADY_ISSUED);

    mockMvc
        .perform(get(TAX).param("invoiceId", String.valueOf(paid)).cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].officeName").value(owner.office().getName()))
        .andExpect(jsonPath("$.items[0].recipientRegistered").value(false))
        .andExpect(jsonPath("$.items[0].dueHint").isNotEmpty())
        .andExpect(jsonPath("$.items[0].isElectronic").value(true));
    assertThat(auditCount("TAX_DOCUMENT_RECORDED")).isEqualTo(2);
  }

  @Test
  void 수정_발급은_원본을_두고_새_행을_만들며_발급됨이_아니면_409다() throws Exception {
    long paid = invoice("PAID", 33_000);
    String created =
        post(
                INVOICES + "/" + paid + "/tax-documents",
                "{\"documentKind\":\"CASH_RECEIPT\",\"isElectronic\":true,\"status\":\"ISSUED\","
                    + "\"approvalNumber\":\"cash-1\",\"issuedOn\":\"2027-02-05\","
                    + "\"supplyAmount\":30000,\"vatAmount\":3000}",
                billingSession)
            .andReturn()
            .getResponse()
            .getContentAsString();
    long original = Long.parseLong(created.replaceAll("(?s).*\"documentId\":(\\d+).*", "$1"));
    String amend =
        "{\"approvalNumber\":\"cash-2\",\"issuedOn\":\"2027-03-02\",\"supplyAmount\":-30000,"
            + "\"vatAmount\":-3000,\"operatorNote\":\"전액 환불\"}";
    String amended =
        post(TAX + "/" + original + "/amend", amend, billingSession)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("AMENDED"))
            .andExpect(jsonPath("$.originalDocumentId").value(original))
            .andExpect(jsonPath("$.supplyAmount").value(-30_000))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long amendedId = Long.parseLong(amended.replaceAll("(?s).*\"documentId\":(\\d+).*", "$1"));
    assertError(
        post(TAX + "/" + amendedId + "/amend", amend, billingSession),
        ErrorCode.TAX_INVOICE_NOT_ELIGIBLE);
    assertFieldError(
        post(TAX + "/" + original + "/amend", "{\"approvalNumber\":\"x\"}", billingSession),
        "issuedOn",
        "REQUIRED");
    assertError(post(TAX + "/999999/amend", amend, billingSession), ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        post(TAX + "/" + original + "/amend", amend, billingSession),
        "approvalNumber",
        "NOT_ALLOWED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM tax_document WHERE id = ?", String.class, original))
        .isEqualTo("ISSUED");
    mockMvc
        .perform(get(TAX).param("status", "AMENDED").cookie(billingSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].originalDocumentId").value(original));
    assertFieldError(
        mockMvc.perform(get(TAX).param("status", "NOPE").cookie(billingSession)),
        "status",
        "NOT_ALLOWED");
    assertThat(auditCount("TAX_DOCUMENT_AMENDED")).isEqualTo(1);
  }
}
