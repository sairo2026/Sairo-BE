package com.sairo.be.domain.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.billing.service.TaxDocumentPendingScanService;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class TaxDocumentPendingScanIntegrationTest extends BillingIntegrationTestBase {

  @Autowired private TaxDocumentPendingScanService scanService;

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

  private long invoice(String status, String start, String end) {
    return fixtures
        .invoice(owner.office().getId(), subscriptionId, consentId)
        .status(status)
        .period(Instant.parse(start), Instant.parse(end))
        .insert();
  }

  private void recipient(boolean issueRequested) {
    jdbcTemplate.update(
        "INSERT INTO tax_invoice_recipient (office_id, business_number, company_name,"
            + " representative_name, receiver_email, issue_requested, registered_by_membership_id)"
            + " VALUES (?, '1234567890', '상호', '대표', 'office@example.com', ?, ?)",
        owner.office().getId(),
        issueRequested,
        owner.membership().getId());
  }

  private List<Map<String, Object>> documents() {
    return jdbcTemplate.queryForList(
        "SELECT billing_invoice_id, document_kind, status, recorded_by_operator_id, recipient_id"
            + " FROM tax_document ORDER BY billing_invoice_id");
  }

  @Test
  void 발급을_요청한_사무소의_결제_성공_청구서마다_발급_대기_기록을_만들고_다시_돌려도_같다() {
    recipient(true);
    long paid = invoice("PAID", "2027-07-01T00:00:00Z", "2027-08-01T00:00:00Z");
    long partial = invoice("PARTIALLY_REFUNDED", "2027-08-01T00:00:00Z", "2027-09-01T00:00:00Z");
    invoice("FAILED", "2027-09-01T00:00:00Z", "2027-10-01T00:00:00Z");
    invoice("FULLY_REFUNDED", "2027-10-01T00:00:00Z", "2027-11-01T00:00:00Z");

    assertThat(scanService.scan(Instant.now())).isEqualTo(2);
    assertThat(scanService.scan(Instant.now())).isZero();

    List<Map<String, Object>> rows = documents();
    assertThat(rows)
        .extracting(row -> row.get("billing_invoice_id"))
        .containsExactly(paid, partial);
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row).containsEntry("document_kind", "TAX_INVOICE");
              assertThat(row).containsEntry("status", "PENDING");
              assertThat(row.get("recorded_by_operator_id")).isNull();
              assertThat(row.get("recipient_id")).isNotNull();
            });
  }

  @Test
  void 발급을_요청하지_않았거나_수신_정보가_없으면_만들지_않고_이미_기록이_있는_청구서는_건너뛴다() {
    invoice("PAID", "2027-07-01T00:00:00Z", "2027-08-01T00:00:00Z");
    assertThat(scanService.scan(Instant.now())).isZero();

    recipient(false);
    assertThat(scanService.scan(Instant.now())).isZero();

    jdbcTemplate.update("UPDATE tax_invoice_recipient SET issue_requested = true");
    long issued = invoice("PAID", "2027-08-01T00:00:00Z", "2027-09-01T00:00:00Z");
    jdbcTemplate.update(
        "INSERT INTO tax_document (billing_invoice_id, document_kind, status, approval_number,"
            + " issued_on, supply_amount, vat_amount, recorded_by_operator_id) VALUES (?,"
            + " 'TAX_INVOICE', 'ISSUED', 'APPROVAL-9', DATE '2027-09-05', 36273, 3627, ?)",
        issued,
        fixtures.creatorOperator());

    assertThat(scanService.scan(Instant.now())).isEqualTo(1);
    assertThat(documents()).hasSize(2);
  }

  @Test
  void 운영자가_기록한_행은_기록자가_필수이고_대기_행만_기록자_없이_허용된다() {
    recipient(true);
    long invoiceId = invoice("PAID", "2027-07-01T00:00:00Z", "2027-08-01T00:00:00Z");

    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbcTemplate.update(
                "INSERT INTO tax_document (billing_invoice_id, document_kind, status,"
                    + " operator_note) VALUES (?, 'TAX_INVOICE', 'ISSUE_FAILED', '사유')",
                invoiceId));
  }
}
