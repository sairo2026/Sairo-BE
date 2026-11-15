package com.sairo.be.domain.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.billing.service.BillingJobService;
import com.sairo.be.domain.billing.service.PlanNoticeJobService;
import com.sairo.be.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class RenewalConsentSafeguardIntegrationTest extends PaymentTestBase {

  @Autowired private BillingJobService jobService;
  @Autowired private PlanNoticeJobService planNoticeJobs;

  private List<String> messages(long userId, String type) {
    return jdbcTemplate.queryForList(
        "SELECT message FROM notification WHERE recipient_user_id = ? AND notification_type = ?"
            + " ORDER BY id",
        String.class,
        userId,
        type);
  }

  private long noticeCount(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM notification WHERE recipient_user_id = ?", Long.class, userId);
  }

  private List<String> emailChannels(long userId, String type) {
    return jdbcTemplate.queryForList(
        "SELECT d.channel FROM notification_delivery d JOIN notification n ON n.id ="
            + " d.notification_id WHERE n.recipient_user_id = ? AND n.notification_type = ?",
        String.class,
        userId,
        type);
  }

  private Ready dueInThreeDays(boolean consent) {
    return ready(
        seed -> seed.status("ACTIVE").period(daysFromNow(-27), daysFromNow(3)), consent, true);
  }

  @Test
  void 유효한_구매_동의가_없으면_갱신_7일_전에_일반_안내_대신_동의_요청을_한_번만_보낸다() {
    Ready ready = dueInThreeDays(false);
    long userId = ready.owner().user().getId();
    Instant now = Instant.now();
    planNoticeJobs.runPaymentUpcomingNotices(now);
    planNoticeJobs.runPaymentUpcomingNotices(now);

    assertThat(messages(userId, "PAYMENT")).isEmpty();
    List<String> notices = messages(userId, "PLAN_CHANGED");
    assertThat(notices).hasSize(1);
    assertThat(notices.getFirst()).contains("구독을 이어가려면 구매 동의가 필요합니다");
    assertThat(emailChannels(userId, "PLAN_CHANGED")).containsExactly("EMAIL");
  }

  @Test
  void 유효한_구매_동의가_있으면_기존_갱신_안내가_그대로_나간다() {
    Ready ready = dueInThreeDays(true);
    long userId = ready.owner().user().getId();
    planNoticeJobs.runPaymentUpcomingNotices(Instant.now());

    assertThat(messages(userId, "PLAN_CHANGED")).isEmpty();
    List<String> notices = messages(userId, "PAYMENT");
    assertThat(notices).hasSize(1);
    assertThat(notices.getFirst()).contains("결제됩니다");
  }

  @Test
  void 갱신_시점에_동의가_없으면_청구서를_만들지_않고_미납유예로_전이하며_안내를_한_번만_보낸다() {
    Ready ready =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-31), daysFromNow(-1)), false, true);
    long userId = ready.owner().user().getId();
    jobService.runRenewals(Instant.now());
    jobService.runRenewals(Instant.now());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                ready.subscriptionId()))
        .isEqualTo("PAST_DUE_GRACE");
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing_invoice", Long.class))
        .isZero();
    assertThat(paymentGateway.charges()).isEmpty();
    List<String> notices = messages(userId, "PAYMENT");
    assertThat(notices).hasSize(1);
    assertThat(notices.getFirst()).contains("구매 동의가 없어 이번 갱신을 처리하지 못했습니다");
    assertThat(noticeCount(userId)).isEqualTo(1L);
    assertThat(emailChannels(userId, "PAYMENT")).containsExactly("EMAIL");
  }

  @Test
  void 동의가_있는_갱신은_안내_없이_청구하고_결제수단이_없는_사무소에는_동의_안내를_보내지_않는다() {
    Ready consented =
        ready(seed -> seed.status("ACTIVE").period(daysFromNow(-31), daysFromNow(-1)), true, true);
    jobService.runRenewals(Instant.now());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing_invoice WHERE subscription_id = ?",
                Long.class,
                consented.subscriptionId()))
        .isEqualTo(1L);
    assertThat(noticeCount(consented.owner().user().getId())).isZero();

    Ready noKey =
        ready(
            seed -> seed.status("ACTIVE").period(daysFromNow(-31), daysFromNow(-1)), false, false);
    jobService.runRenewals(Instant.now());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?",
                String.class,
                noKey.subscriptionId()))
        .isEqualTo("PAST_DUE_GRACE");
    assertThat(noticeCount(noKey.owner().user().getId())).isZero();
  }
}
