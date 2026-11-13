package com.sairo.be.domain.ops.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sairo.be.domain.auth.repository.ConsentNoticeBatchRepository;
import com.sairo.be.domain.auth.repository.ConsentNoticeBatchRepository.DueNotice;
import com.sairo.be.domain.auth.service.ConsentNoticeMailComposer;
import com.sairo.be.domain.auth.service.ConsentNoticeSendService;
import com.sairo.be.domain.auth.service.MarketingUnsubscribeTokens;
import com.sairo.be.global.config.CompanyProperties;
import com.sairo.be.global.port.MailSender;
import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.security.SecurityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsentNoticeSendServiceTest {

  private static final Instant NOW = Instant.parse("2027-03-01T03:00:00Z");

  private final ConsentNoticeBatchRepository repository = mock(ConsentNoticeBatchRepository.class);
  private final MailSender mailSender = mock(MailSender.class);
  private final ConsentNoticeMailComposer composer = mock(ConsentNoticeMailComposer.class);
  private final ConsentNoticeSendService service =
      new ConsentNoticeSendService(
          repository, composer, mailSender, Clock.fixed(NOW, ZoneOffset.UTC));

  private DueNotice notice(int attempts, Instant dueAt) {
    return new DueNotice(
        7L,
        1L,
        11L,
        "MARKETING_RESULT",
        NOW.minusSeconds(60),
        dueAt,
        attempts,
        "이름",
        "a@example.com",
        true,
        "ACCOUNT_SETTINGS",
        11L);
  }

  private void failMail() {
    when(composer.result(any(), any(), any(), any(), anyBoolean(), anyBoolean()))
        .thenReturn(new MailMessage("a@example.com", "s", "b"));
    doThrow(new IllegalStateException("fail")).when(mailSender).send(any());
  }

  @Test
  void 지수_간격은_10분에서_시작해_두_배씩_늘고_12시간에서_멈춘다() {
    assertThat(ConsentNoticeSendService.delayAfterFailure(1)).isEqualTo(Duration.ofMinutes(10));
    assertThat(ConsentNoticeSendService.delayAfterFailure(2)).isEqualTo(Duration.ofMinutes(20));
    assertThat(ConsentNoticeSendService.delayAfterFailure(4)).isEqualTo(Duration.ofMinutes(80));
    assertThat(ConsentNoticeSendService.delayAfterFailure(30)).isEqualTo(Duration.ofHours(12));
  }

  @Test
  void 발송에_실패하면_목표_시각을_미룬다() {
    when(repository.claimDue(eq(NOW), anyInt()))
        .thenReturn(List.of(notice(0, NOW.plus(Duration.ofDays(14)))));
    failMail();

    var result = service.sendDue();

    assertThat(result.retried()).isEqualTo(1);
    verify(repository).reschedule(eq(7L), eq(1), eq(NOW.plus(Duration.ofMinutes(10))), any());
  }

  @Test
  void 다음_시도가_기한을_넘으면_기한_시각으로_잡는다() {
    Instant due = NOW.plus(Duration.ofMinutes(5));
    when(repository.claimDue(eq(NOW), anyInt())).thenReturn(List.of(notice(0, due)));
    failMail();

    service.sendDue();

    verify(repository).reschedule(eq(7L), eq(1), eq(due), any());
  }

  @Test
  void 기한이_지난_뒤에도_실패하면_실패로_닫는다() {
    when(repository.claimDue(eq(NOW), anyInt()))
        .thenReturn(List.of(notice(5, NOW.minus(Duration.ofHours(1)))));
    failMail();

    var result = service.sendDue();

    assertThat(result.closed()).isEqualTo(1);
    verify(repository)
        .markClosed(eq(7L), eq("FAILED"), eq(6), eq(ConsentNoticeSendService.DEADLINE_PASSED));
  }

  @Test
  void 발송_문구는_상호와_문의를_넣고_수신_거부_링크를_붙인다() {
    ConsentNoticeMailComposer real =
        new ConsentNoticeMailComposer(
            new CompanyProperties(
                "(주)예시", null, null, null, null, null, "help@example.com", null, null),
            new MarketingUnsubscribeTokens(
                new SecurityProperties("t", "r", "k", 1, "secret-for-test-only-0123456789", "e")),
            "https://app.example.com");

    MailMessage mail = real.reconfirm("a@example.com", 5L, "김회원", NOW);

    assertThat(mail.body())
        .contains("김회원님은 2027년 3월 1일에", "보낸 곳 (주)예시 / 문의 help@example.com.")
        .contains("[수신 거부] https://app.example.com/unsubscribe?token=5.");
  }

  @Test
  void 상호가_없으면_기본_상호를_쓰고_문의가_없으면_문의를_뺀다() {
    ConsentNoticeMailComposer real =
        new ConsentNoticeMailComposer(
            new CompanyProperties(null, null, null, null, null, null, " ", null, null),
            new MarketingUnsubscribeTokens(
                new SecurityProperties("t", "r", "k", 1, "secret-for-test-only-0123456789", "e")),
            "https://app.example.com");

    MailMessage mail = real.result("a@example.com", 5L, "김회원", NOW, false, true);

    assertThat(mail.body())
        .contains("요청 내용 수신 거부", "보낸 곳 사이로.")
        .doesNotContain("문의")
        .doesNotContain("/unsubscribe");
  }
}
