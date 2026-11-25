package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
class PromotionLedgerIntegrationTest extends AuthIntegrationTestBase {
  @Autowired private PromotionService promotions;
  @Autowired private BillingJobService billingJob;
  @Autowired private StubPaymentGateway gateway;
  @Autowired private InvoiceIssuer invoiceIssuer;
  @Autowired private AuthSuspensionService authSuspensions;
  @Autowired private OfficeCloseService officeClose;
  @Autowired private StepUpState stepUp;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private SubscriptionRepository subscriptions;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository membershipRepository;

  private BillingFixtures fixtures;

  @BeforeEach
  void setup() {
    fixtures = new BillingFixtures(jdbcTemplate);
    gateway.reset();
    fixtures.standardPrice("2026-PROMO-STANDARD", Instant.now().minus(400, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
  }

  @Test
  void 무료_월별_거래는_정상가와_할인을_기록하고_PG_청구를_만들지_않는다() {
    Seed seed = seed(3);
    Instant secondStart = BillingPeriods.boundary(seed.anchor(), 1);

    promotions.runDue(secondStart.plusSeconds(1));

    assertThat(count("SELECT count(*) FROM promotion_period")).isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM promotion_period WHERE list_amount = discount_amount"
                    + " AND charged_amount = 0"))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isZero();
    assertThat(gateway.charges()).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                seed.subscriptionId()))
        .isEqualTo("TRIAL");

    promotions.runDue(secondStart.plusSeconds(1));
    assertThat(count("SELECT count(*) FROM promotion_period")).isEqualTo(2);
    long officeId = seed.owner().office().getId();
    String issuedName =
        jdbcTemplate.queryForObject(
            "SELECT office_name_snapshot FROM promotion_period WHERE subscription_id = ?"
                + " AND period_start = ?",
            String.class,
            seed.subscriptionId(),
            Timestamp.from(seed.anchor()));
    jdbcTemplate.update("UPDATE office SET name = 'renamed-after-issue' WHERE id = ?", officeId);
    assertThat(promotions.periodsForOffice(officeId))
        .allSatisfy(
            period -> {
              assertThat(period.officeName()).isEqualTo(issuedName);
              assertThat(period.plan()).isEqualTo("SOLO");
            });
  }

  @Test
  void 무료_종료_후_명시적_구매_근거가_없으면_청구_대기로_넘기고_카드를_호출하지_않는다() {
    Seed seed = seed(1);
    Instant end = BillingPeriods.boundary(seed.anchor(), 1);

    promotions.runDue(end);
    billingJob.runFirstCharges(end.plusSeconds(1));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, seed.grantId()))
        .isEqualTo("CONVERSION_PENDING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                seed.subscriptionId()))
        .isEqualTo("PRE_PAYMENT");
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void 월말_기준일로_24개월을_건너뛰어도_매월_한_건만_기록하고_원래_31일로_복귀한다() {
    Instant anchor = Instant.parse("2027-01-31T05:30:00Z"); // KST 14:30
    Seed seed = seed(24, anchor);

    for (int month = 1; month <= 24; month++) {
      promotions.runDue(BillingPeriods.boundary(anchor, month));
    }

    assertThat(count("SELECT count(*) FROM promotion_period")).isEqualTo(24);
    assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT period_end FROM promotion_period WHERE subscription_id = ?"
                        + " AND period_start = ?",
                    java.sql.Timestamp.class,
                    seed.subscriptionId(),
                    Timestamp.from(anchor))
                .toInstant())
        .isEqualTo(BillingPeriods.boundary(anchor, 1));
    assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT period_end FROM promotion_period WHERE subscription_id = ?"
                        + " AND period_start = ?",
                    java.sql.Timestamp.class,
                    seed.subscriptionId(),
                    Timestamp.from(BillingPeriods.boundary(anchor, 1)))
                .toInstant())
        .isEqualTo(BillingPeriods.boundary(anchor, 2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, seed.grantId()))
        .isEqualTo("CONVERSION_PENDING");
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isZero();
    assertThat(gateway.charges()).isEmpty();
    promotions.runDue(BillingPeriods.boundary(anchor, 24));
    assertThat(count("SELECT count(*) FROM promotion_period")).isEqualTo(24);
  }

  @Test
  void 무료_지급_이전_구매동의는_무료_종료_뒤_유료_결제_근거가_되지_않는다() {
    Seed seed = seed(1);
    fixtures.consent(
        seed.subscriptionId(),
        seed.owner().user().getId(),
        seed.owner().office().getId(),
        39_900,
        3_627,
        39_900);
    Instant end = BillingPeriods.boundary(seed.anchor(), 1);
    promotions.runDue(end);
    var subscription = subscriptions.findById(seed.subscriptionId()).orElseThrow();

    assertThat(invoiceIssuer.issue(subscription, end, BillingPeriods.boundary(end, 1), end))
        .isEmpty();
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void overdue_period_is_not_backfilled_with_current_member_count() {
    Seed seed = seed(3);
    Instant secondStart = BillingPeriods.boundary(seed.anchor(), 1);

    promotions.runDue(secondStart.plus(2, ChronoUnit.DAYS));

    assertThat(count("SELECT count(*) FROM promotion_period")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, seed.grantId()))
        .isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(gateway.charges()).isEmpty();
    fixtures.consent(
        seed.subscriptionId(),
        seed.owner().user().getId(),
        seed.owner().office().getId(),
        39_900,
        3_627,
        39_900);
    assertThat(
            invoiceIssuer.issue(
                subscriptions.findById(seed.subscriptionId()).orElseThrow(),
                secondStart,
                BillingPeriods.boundary(secondStart, 1),
                secondStart))
        .isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'promotion-free-renewal'"
                    + " AND office_id = ?",
                String.class,
                seed.owner().office().getId()))
        .isEqualTo("SUCCESS");
  }

  @Test
  void authentication_suspension_preserves_free_end_and_pauses_renewal() {
    Seed seed = seed(3);
    long officeId = seed.owner().office().getId();
    Instant suspendedAt = seed.anchor().plus(1, ChronoUnit.DAYS);
    authSuspensions.suspend(officeId, suspendedAt);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, seed.grantId()))
        .isEqualTo("SUSPENDED");

    promotions.runDue(BillingPeriods.boundary(seed.anchor(), 1));
    assertThat(count("SELECT count(*) FROM promotion_period")).isEqualTo(1);
    authSuspensions.resume(officeId, suspendedAt.plus(2, ChronoUnit.DAYS));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, seed.grantId()))
        .isEqualTo("ACTIVE");
    assertThat(subscriptions.findById(seed.subscriptionId()).orElseThrow().getTrialEndsAt())
        .isEqualTo(BillingPeriods.boundary(seed.anchor(), 3));
  }

  @Test
  void effective_cancellation_ends_free_grant_before_month_boundary() {
    Seed seed = seed(3);
    Instant effectiveAt = Instant.now().minusSeconds(60);
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, effective_at, cancelled_by_membership_id)"
            + " VALUES (?, ?, ?)",
        seed.subscriptionId(),
        Timestamp.from(effectiveAt),
        seed.owner().membership().getId());

    promotions.runDue(Instant.now());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, seed.grantId()))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                seed.subscriptionId()))
        .isEqualTo("ENDED");
    assertThat(count("SELECT count(*) FROM promotion_period")).isEqualTo(1);
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void office_close_ends_free_grant_immediately() throws Exception {
    Seed seed = seed(3);
    var owner = seed.owner();
    var principal =
        new StaffPrincipal(
            owner.user().getId(),
            owner.membership().getId(),
            owner.office().getId(),
            owner.user().getName(),
            StaffRole.OWNER);
    var session = new MockHttpSession();
    stepUp.grant(session);

    officeClose.close(
        principal,
        session,
        objectMapper.readTree("{\"confirmed\":true,\"archiveChoice\":\"DECLINE\"}"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, seed.grantId()))
        .isEqualTo("ENDED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                seed.subscriptionId()))
        .isEqualTo("ENDED");
    assertThat(gateway.charges()).isEmpty();
  }

  @Test
  void fresh_purchase_consent_after_free_end_can_create_a_real_invoice() {
    Instant anchor = Instant.now().minus(45, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    Seed seed = seed(1, anchor);
    Instant end = BillingPeriods.boundary(anchor, 1);
    promotions.runDue(end);
    fixtures.consent(
        seed.subscriptionId(),
        seed.owner().user().getId(),
        seed.owner().office().getId(),
        39_900,
        3_627,
        39_900);

    var subscription = subscriptions.findById(seed.subscriptionId()).orElseThrow();
    assertThat(invoiceIssuer.issue(subscription, end, BillingPeriods.boundary(end, 1), end))
        .isPresent();
    assertThat(count("SELECT count(*) FROM billing_invoice")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isZero();
    assertThat(gateway.charges()).isEmpty();
  }

  private Seed seed(int months) {
    return seed(months, Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
  }

  private Seed seed(int months, Instant anchor) {
    var owner =
        new TestData(appUserRepository, officeRepository, membershipRepository)
            .approvedStaff(OfficeMembershipRole.OWNER);
    long officeId = owner.office().getId();
    Instant end = BillingPeriods.boundary(anchor, months);
    long event =
        fixtures.trialEvent(
            "GENERAL", "DURATION", anchor.minusSeconds(100), end.plusSeconds(100), null, "ACTIVE");
    long subscriptionId =
        fixtures.subscription(officeId).status("TRIAL").trial(event, anchor, end).insert();
    long operatorId =
        jdbcTemplate.queryForObject(
            "SELECT created_by_operator_id FROM trial_event WHERE id = ?", Long.class, event);
    long campaignId =
        jdbcTemplate.queryForObject(
            "INSERT INTO promotion_campaign (name, code, application_starts_at,"
                + " application_ends_at, free_months, status, created_by_operator_id,"
                + " activated_by_operator_id) VALUES ('test', ?, ?, ?, ?, 'ACTIVE', ?, ?)"
                + " RETURNING id",
            Long.class,
            "TEST-" + officeId,
            Timestamp.from(anchor.minusSeconds(100)),
            Timestamp.from(end.plusSeconds(100)),
            months,
            operatorId,
            fixtures.approverOperator());
    long grantId =
        jdbcTemplate.queryForObject(
            "INSERT INTO promotion_grant (campaign_id, office_id, subscription_id, status,"
                + " starts_at, free_ends_at, billing_anchor_at, next_period_start,"
                + " granted_by_operator_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?) RETURNING id",
            Long.class,
            campaignId,
            officeId,
            subscriptionId,
            Timestamp.from(anchor),
            Timestamp.from(end),
            Timestamp.from(anchor),
            Timestamp.from(BillingPeriods.boundary(anchor, 1)),
            operatorId);
    jdbcTemplate.update(
        "INSERT INTO promotion_period (grant_id, office_id, subscription_id, period_start,"
            + " period_end, list_amount, discount_amount, charged_amount, price_version,"
            + " member_count_snapshot, office_name_snapshot, plan_snapshot)"
            + " VALUES (?, ?, ?, ?, ?, 39900, 39900, 0,"
            + " '2026-PROMO-STANDARD', 1, (SELECT name FROM office WHERE id = ?), 'SOLO')",
        grantId,
        officeId,
        subscriptionId,
        Timestamp.from(anchor),
        Timestamp.from(BillingPeriods.boundary(anchor, 1)),
        officeId);
    return new Seed(anchor, subscriptionId, grantId, owner);
  }

  private long count(String sql) {
    return jdbcTemplate.queryForObject(sql, Long.class);
  }

  private record Seed(Instant anchor, long subscriptionId, long grantId, TestData.Staff owner) {}
}
