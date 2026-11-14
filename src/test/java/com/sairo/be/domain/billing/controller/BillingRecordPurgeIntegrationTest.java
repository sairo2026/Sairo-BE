package com.sairo.be.domain.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.legal.service.RetentionGroupPurger;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

/** 구독·결제 기록과 세금 증빙의 보관 기한 파기. 시험 데이터만 만들고 지운다. */
@IntegrationTest
class BillingRecordPurgeIntegrationTest extends BillingIntegrationTestBase {

  @Autowired private RetentionGroupPurger purger;

  @BeforeEach
  void seed() throws Exception {
    jdbcTemplate.execute(
        new ClassPathResource("db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql")
            .getContentAsString(StandardCharsets.UTF_8));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
  }

  private record Seed(TestData.Staff owner, long subscriptionId, long invoiceId) {}

  private Seed endedSubscription(String endedAgo, String invoiceAgo) {
    TestData.Staff owner = owner();
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ENDED")
            .period(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"))
            .insert();
    long consentId =
        fixtures.consent(
            subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    long invoiceId =
        fixtures
            .invoice(owner.office().getId(), subscriptionId, consentId)
            .status("PAID")
            .period(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-31T00:00:00Z"))
            .insert();
    jdbcTemplate.update(
        "UPDATE subscription SET ended_at = now() - ?::interval WHERE id = ?",
        endedAgo,
        subscriptionId);
    jdbcTemplate.update(
        "UPDATE billing_invoice SET created_at = now() - ?::interval WHERE id = ?",
        invoiceAgo,
        invoiceId);
    jdbcTemplate.update(
        "INSERT INTO billing_charge_attempt (invoice_id, attempt_no, result, pg_order_id,"
            + " idempotency_key) VALUES (?, 1, 'SUCCESS', ?, ?)",
        invoiceId,
        "ORD-" + invoiceId + "-001",
        java.util.UUID.randomUUID().toString());
    return new Seed(owner, subscriptionId, invoiceId);
  }

  private long count(String table, String where) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
  }

  @Test
  void 종료_뒤_기한이_지난_구독은_결제_기록과_함께_지우고_기한_안이거나_세금_증빙이_남은_구독은_남긴다() {
    Seed old = endedSubscription("2000 days", "2000 days");
    Seed recent = endedSubscription("100 days", "100 days");
    Seed taxed = endedSubscription("2000 days", "2000 days");
    jdbcTemplate.update(
        "INSERT INTO tax_document (billing_invoice_id, document_kind, status, operator_note,"
            + " recorded_by_operator_id, recorded_at) VALUES (?, 'TAX_INVOICE', 'ISSUE_FAILED',"
            + " '사유', ?, now())",
        taxed.invoiceId(),
        fixtures.creatorOperator());

    assertThat(purger.dueCount(RetentionGroupPurger.BILLING_RECORD, Instant.now())).isEqualTo(1);
    purger.execute(RetentionGroupPurger.BILLING_RECORD, Instant.now());

    assertThat(count("subscription", "id = " + old.subscriptionId())).isZero();
    assertThat(count("billing_invoice", "id = " + old.invoiceId())).isZero();
    assertThat(count("billing_charge_attempt", "invoice_id = " + old.invoiceId())).isZero();
    assertThat(count("subscription_purchase_consent", "subscription_id = " + old.subscriptionId()))
        .isZero();
    assertThat(count("subscription", "id = " + recent.subscriptionId())).isEqualTo(1);
    assertThat(count("subscription", "id = " + taxed.subscriptionId())).isEqualTo(1);
    assertThat(count("billing_invoice", "id = " + taxed.invoiceId())).isEqualTo(1);
  }

  @Test
  void 세금_증빙은_발급_뒤_기한이_지난_발급_기록만_지우고_수정_발급을_원본보다_먼저_지운다() {
    Seed seed = endedSubscription("2000 days", "2000 days");
    long operator = fixtures.creatorOperator();
    long original =
        jdbcTemplate.queryForObject(
            "INSERT INTO tax_document (billing_invoice_id, document_kind, status, approval_number,"
                + " issued_on, supply_amount, vat_amount, recorded_by_operator_id) VALUES (?,"
                + " 'TAX_INVOICE', 'ISSUED', 'APP-OLD', current_date - 2000, 100, 10, ?) RETURNING"
                + " id",
            Long.class,
            seed.invoiceId(),
            operator);
    jdbcTemplate.update(
        "INSERT INTO tax_document (billing_invoice_id, document_kind, status, approval_number,"
            + " issued_on, supply_amount, vat_amount, original_document_id, operator_note,"
            + " recorded_by_operator_id) VALUES (?, 'TAX_INVOICE', 'AMENDED', 'APP-AMEND',"
            + " current_date - 1999, -100, -10, ?, '수정', ?)",
        seed.invoiceId(),
        original,
        operator);
    jdbcTemplate.update(
        "INSERT INTO tax_document (billing_invoice_id, document_kind, status,"
            + " recorded_by_operator_id) VALUES (?, 'CASH_RECEIPT', 'PENDING', ?)",
        seed.invoiceId(),
        operator);

    assertThat(purger.dueCount(RetentionGroupPurger.TAX_DOCUMENT, Instant.now())).isEqualTo(2);
    purger.execute(RetentionGroupPurger.TAX_DOCUMENT, Instant.now());

    assertThat(count("tax_document", "billing_invoice_id = " + seed.invoiceId())).isEqualTo(1);
    assertThat(count("tax_document", "status = 'PENDING'")).isEqualTo(1);
  }
}
