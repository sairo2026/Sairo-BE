package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.stub.StubNotificationChannelSender;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.BillingFixtures.SubscriptionSeed;
import com.sairo.be.support.fixture.TestData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

@IntegrationTest
class BillingNoticeIntegrationTest extends AuthIntegrationTestBase {

  @Autowired private BillingJobService jobService;
  @Autowired private StubPaymentGateway gateway;
  @Autowired private StubNotificationChannelSender channelSender;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  @Autowired
  @Qualifier("billingKeyCipher") private TextCipher cipher;

  private BillingFixtures fixtures;
  private Long trialEvent;

  @BeforeEach
  void seed() {
    fixtures = new BillingFixtures(jdbcTemplate);
    gateway.reset();
    channelSender.clear();
    fixtures.standardPrice("2026-12-STD", Instant.now().minus(400, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    trialEvent =
        fixtures.trialEvent(
            "GENERAL", "DURATION", daysFromNow(-90), daysFromNow(60), null, "ACTIVE");
  }

  private static Instant daysFromNow(long days) {
    return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
  }

  private record Ready(TestData.Staff owner, long subscriptionId, long consentId) {}

  private Ready ready(UnaryOperator<SubscriptionSeed> seed, boolean consent, boolean key) {
    TestData.Staff owner =
        new TestData(appUserRepository, officeRepository, officeMembershipRepository)
            .approvedStaff(OfficeMembershipRole.OWNER);
    long subscriptionId = seed.apply(fixtures.subscription(owner.office().getId())).insert();
    long consentId = 0;
    if (consent) {
      consentId =
          fixtures.consent(
              subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    }
    if (key) {
      fixtures.billingCustomerWithKey(
          owner.office().getId(),
          "customer-" + owner.office().getId(),
          cipher.encrypt("billing-key"));
    }
    return new Ready(owner, subscriptionId, consentId);
  }

  private List<Map<String, Object>> notices(Ready ready) {
    return jdbcTemplate.queryForList(
        "SELECT notification_type, message, target_type, target_id, office_id FROM notification"
            + " WHERE recipient_user_id = ? ORDER BY id",
        ready.owner().user().getId());
  }

  @Test
  void 무료_종료_안내는_지난_가장_가까운_기준일_하나만_한_번_보내고_해지를_예약하면_보내지_않는다() {
    Ready soon =
        ready(
            seed -> seed.status("TRIAL").trial(trialEvent, daysFromNow(-55), daysFromNow(5)),
            true,
            true);
    Ready far =
        ready(
            seed -> seed.status("TRIAL").trial(trialEvent, daysFromNow(-10), daysFromNow(50)),
            true,
            true);
    Ready cancelled =
        ready(
            seed -> seed.status("TRIAL").trial(trialEvent, daysFromNow(-55), daysFromNow(5)),
            true,
            true);
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, effective_at,"
            + " cancelled_by_membership_id) VALUES (?, ?, ?)",
        cancelled.subscriptionId(),
        java.sql.Timestamp.from(daysFromNow(5)),
        cancelled.owner().membership().getId());

    jobService.runTrialEndingNotices(Instant.now());
    jobService.runTrialEndingNotices(Instant.now());

    List<Map<String, Object>> rows = notices(soon);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("notification_type")).isEqualTo("TRIAL_ENDING");
    assertThat(rows.get(0).get("target_type")).isEqualTo("SUBSCRIPTION");
    assertThat(rows.get(0).get("target_id")).isNull();
    assertThat((String) rows.get(0).get("message"))
        .isEqualTo("무료 이용이 7일 뒤 끝납니다. 구독 화면에서 결제 준비를 확인해 주세요.");
    assertThat(notices(far)).isEmpty();
    assertThat(notices(cancelled)).isEmpty();
    assertThat(channelSender.delivered())
        .filteredOn(delivery -> delivery.type().equals("TRIAL_ENDING"))
        .hasSize(1);

    jobService.runTrialEndingNotices(daysFromNow(4).plus(1, ChronoUnit.HOURS));
    assertThat(notices(soon)).hasSize(2);
    assertThat((String) notices(soon).get(1).get("message")).startsWith("무료 이용이 1일 뒤");
  }

  @Test
  void 무료가_끝났는데_결제_준비가_없으면_결제_전_상태_안내를_남긴다() {
    Ready ready =
        ready(
            seed -> seed.status("TRIAL").trial(trialEvent, daysFromNow(-61), daysFromNow(-1)),
            true,
            false);
    jobService.runFirstCharges(Instant.now());
    jobService.runFirstCharges(Instant.now());
    List<Map<String, Object>> rows = notices(ready);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("notification_type")).isEqualTo("PAYMENT");
    assertThat(rows.get(0).get("message")).isEqualTo("결제 준비가 필요합니다");
  }

  @Test
  void 갱신_결제가_실패하면_첫_실패에만_결제_실패_안내를_남기고_재시도_실패는_다시_알리지_않는다() {
    Instant periodEnd = daysFromNow(-10);
    Ready ready =
        ready(
            seed -> seed.status("ACTIVE").period(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd),
            true,
            true);
    gateway.respondWith(PaymentStatus.FAILED);
    jobService.runRenewals(Instant.now());
    jobService.runRetries(Instant.now());
    assertThat(
            jdbcTemplate.queryForObject("SELECT count(*) FROM billing_charge_attempt", Long.class))
        .isEqualTo(2);
    List<Map<String, Object>> rows = notices(ready);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("notification_type")).isEqualTo("PAYMENT");
    assertThat(rows.get(0).get("message")).isEqualTo("결제에 실패했습니다");
  }

  @Test
  void 미납_제한이_시작되고_미납으로_종료되면_각각_한_번씩_안내한다() {
    Instant d0 = daysFromNow(-9);
    Ready ready =
        ready(
            seed ->
                seed.status("PAST_DUE_GRACE")
                    .period(d0.minus(30, ChronoUnit.DAYS), d0)
                    .pastDueSince(d0),
            true,
            true);
    fixtures
        .invoice(ready.owner().office().getId(), ready.subscriptionId(), ready.consentId())
        .status("FAILED")
        .period(d0, d0.plus(30, ChronoUnit.DAYS))
        .insert();
    jobService.runDelinquency(Instant.now());
    jobService.runDelinquency(Instant.now());
    assertThat(notices(ready))
        .extracting(row -> row.get("message"))
        .containsExactly("미납으로 업무 입력이 제한됩니다");

    jobService.runDelinquency(d0.plus(30, ChronoUnit.DAYS));
    jobService.runDelinquency(d0.plus(31, ChronoUnit.DAYS));
    assertThat(notices(ready))
        .extracting(row -> row.get("message"))
        .containsExactly("미납으로 업무 입력이 제한됩니다", "구독이 종료되었습니다");
    assertThat(notices(ready))
        .extracting(row -> row.get("office_id"))
        .containsOnly(ready.owner().office().getId());
  }
}
