package com.sairo.be.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.service.ConsentNoticeSendService;
import com.sairo.be.domain.auth.service.MarketingConsentService;
import com.sairo.be.domain.auth.service.MarketingUnsubscribeTokens;
import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.support.AuthIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.AppUserBuilder;
import com.sairo.be.support.fixture.TermsFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ConsentNoticeJobIntegrationTest extends AuthIntegrationTestBase {

  private static final Pattern TOKEN = Pattern.compile("/unsubscribe\\?token=(\\S+)");

  @Autowired private ConsentNoticeJobService jobService;
  @Autowired private ConsentNoticeSendService sendService;
  @Autowired private MarketingConsentService consentService;
  @Autowired private MarketingUnsubscribeTokens tokens;

  private AppUser user(String email) {
    return saveEmailUser(email, null);
  }

  private void consent(
      AppUser user, String version, boolean agreed, ConsentPath path, Instant at, boolean email) {
    consentService.record(user.getId(), version, agreed, path, at, email);
  }

  @Test
  void 수신_동의_결과_통지는_수신_거부_링크와_함께_발송되고_한_번만_나간다() {
    AppUser user = user("agree@example.com");
    consent(user, TermsFixtures.VERSION, true, ConsentPath.ACCOUNT_SETTINGS, Instant.now(), true);

    var result = jobService.send();

    assertThat(result.sent()).isEqualTo(1);
    List<MailMessage> sent = mailSender.sent();
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).to()).isEqualTo("agree@example.com");
    assertThat(sent.get(0).subject()).isEqualTo("[사이로] 공지·혜택 안내 수신 설정이 변경되었습니다");
    assertThat(sent.get(0).body()).contains("요청 내용 수신 동의", "앞으로 공지·혜택 안내를 보내 드립니다.", "[수신 거부]");
    Matcher matcher = TOKEN.matcher(sent.get(0).body());
    assertThat(matcher.find()).isTrue();
    assertThat(tokens.verify(matcher.group(1))).contains(user.getId());
    assertThat(
            jdbcTemplate.queryForObject("SELECT status FROM consent_notice_delivery", String.class))
        .isEqualTo("SENT");

    assertThat(jobService.send().sent()).isZero();
    assertThat(mailSender.sent()).hasSize(1);
  }

  @Test
  void 수신_거부_결과_통지에는_수신_거부_링크가_없다() {
    AppUser user = user("leave@example.com");
    consent(user, TermsFixtures.VERSION, true, ConsentPath.ACCOUNT_SETTINGS, Instant.now(), true);
    jobService.send();
    mailSender.clear();
    consent(user, TermsFixtures.VERSION, false, ConsentPath.UNSUBSCRIBE_LINK, Instant.now(), true);

    jobService.send();

    assertThat(mailSender.sent()).hasSize(1);
    assertThat(mailSender.sent().get(0).body())
        .contains("요청 내용 수신 거부", "발송 대상에서 제외했습니다")
        .doesNotContain("/unsubscribe");
  }

  @Test
  void 인증된_이메일이_없으면_이메일_없음으로_닫고_보내지_않는다() {
    AppUser kakaoUser = appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build());
    consent(kakaoUser, TermsFixtures.VERSION, true, ConsentPath.SIGNUP_KAKAO, Instant.now(), true);

    var result = jobService.send();

    assertThat(result.skipped()).isEqualTo(1);
    assertThat(mailSender.sent()).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject("SELECT status FROM consent_notice_delivery", String.class))
        .isEqualTo("SKIPPED_NO_EMAIL");
  }

  @Test
  void 동의한_지_2년_주기의_30일_전에_닿은_회원만_재확인_대상이_되고_다시_돌려도_하나다() {
    Instant now = Instant.now();
    AppUser due = user("due@example.com");
    AppUser young = user("young@example.com");
    AppUser withdrawn = user("out@example.com");
    AppUser oldVersion = user("oldversion@example.com");
    Instant nearCycleEnd = now.minus(Duration.ofDays(2 * 365 - 20));
    consent(due, TermsFixtures.VERSION, true, ConsentPath.SIGNUP_EMAIL, nearCycleEnd, true);
    consent(
        young,
        TermsFixtures.VERSION,
        true,
        ConsentPath.SIGNUP_EMAIL,
        now.minus(Duration.ofDays(400)),
        true);
    consent(withdrawn, TermsFixtures.VERSION, true, ConsentPath.SIGNUP_EMAIL, nearCycleEnd, true);
    consent(
        withdrawn,
        TermsFixtures.VERSION,
        false,
        ConsentPath.ACCOUNT_SETTINGS,
        now.minus(Duration.ofDays(10)),
        true);
    TermsFixtures.insert(
        jdbcTemplate,
        "MARKETING",
        "2025-01-01",
        "이전 마케팅 동의",
        "MARKETING",
        false,
        "2025-01-01T00:00:00Z",
        false,
        null);
    consent(oldVersion, "2025-01-01", true, ConsentPath.SIGNUP_EMAIL, nearCycleEnd, true);

    assertThat(jobService.scanReconfirm(now)).isTrue();
    assertThat(jobService.scanReconfirm(now)).isFalse();

    List<Map<String, Object>> reconfirms =
        jdbcTemplate.queryForList(
            "SELECT user_id, cycle_no, status FROM consent_notice_delivery WHERE notice_type ="
                + " 'MARKETING_RECONFIRM'");
    assertThat(reconfirms).hasSize(1);
    assertThat(reconfirms.get(0))
        .containsEntry("user_id", due.getId())
        .containsEntry("cycle_no", 1)
        .containsEntry("status", "PENDING");
  }

  @Test
  void 재확인_이메일은_동의한_날짜와_수신_거부_링크를_담고_그_사이_철회하면_보내지_않는다() {
    Instant now = Instant.now();
    AppUser active = user("keep@example.com");
    AppUser gone = user("gone@example.com");
    Instant agreed = now.minus(Duration.ofDays(2 * 365 - 20));
    consent(active, TermsFixtures.VERSION, true, ConsentPath.SIGNUP_EMAIL, agreed, true);
    consent(gone, TermsFixtures.VERSION, true, ConsentPath.SIGNUP_EMAIL, agreed, true);
    jobService.scanReconfirm(now);
    jdbcTemplate.update(
        "UPDATE consent_notice_delivery SET status = 'SENT', sent_at = now() WHERE notice_type ="
            + " 'MARKETING_RESULT'");
    consent(gone, TermsFixtures.VERSION, false, ConsentPath.ACCOUNT_SETTINGS, now, false);

    var result = sendService.sendDue();

    assertThat(result.sent()).isEqualTo(1);
    assertThat(result.closed()).isEqualTo(1);
    assertThat(mailSender.sent()).hasSize(1);
    MailMessage mail = mailSender.sent().get(0);
    assertThat(mail.to()).isEqualTo("keep@example.com");
    assertThat(mail.subject()).isEqualTo("[사이로] 공지·혜택 안내 수신 동의 내역을 알려 드립니다");
    assertThat(mail.body()).contains("에 사이로 공지·혜택 안내 수신에 동의하셨습니다", "/unsubscribe?token=");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT failure_code FROM consent_notice_delivery WHERE user_id = ? AND notice_type"
                    + " = 'MARKETING_RECONFIRM'",
                String.class,
                gone.getId()))
        .isEqualTo("CONSENT_NO_LONGER_ACTIVE");
  }

  @Test
  void 발송_기한_7일_전까지_보내지_못한_통지는_통지마다_한_번만_실패_행으로_드러난다() {
    AppUser user = user("late@example.com");
    consent(user, TermsFixtures.VERSION, true, ConsentPath.ACCOUNT_SETTINGS, Instant.now(), true);
    jdbcTemplate.update(
        "UPDATE consent_notice_delivery SET due_at = now() + interval '6 days', target_at = now()"
            + " - interval '8 days', expressed_at = now() - interval '8 days'");

    jobService.warnAtRisk(Instant.now());
    jobService.warnAtRisk(Instant.now());

    List<Map<String, Object>> runs =
        jdbcTemplate.queryForList(
            "SELECT status, error_code, attempt_count FROM system_job_run WHERE job_name ="
                + " 'consent-notice-send'");
    assertThat(runs).hasSize(1);
    assertThat(runs.get(0))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "NOTICE_DEADLINE_AT_RISK")
        .containsEntry("attempt_count", 1);
  }
}
