package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.ConsentNoticeBatchRepository;
import com.sairo.be.domain.auth.repository.ConsentNoticeBatchRepository.DueNotice;
import com.sairo.be.global.port.MailSender;
import com.sairo.be.global.port.MailSender.MailMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수신 설정 처리 결과 통지와 2년 재확인 안내를 이메일로 보낸다. 인증된 이메일이 없으면 SKIPPED_NO_EMAIL로 닫는다. 실패하면 지수 간격으로 목표 시각을 미루고
 * 발송 기한이 지나도 보내지 못하면 FAILED로 닫는다.
 */
@Profile("!migrate")
@Service
public class ConsentNoticeSendService {

  public static final String RESULT = "MARKETING_RESULT";
  public static final String RECONFIRM = "MARKETING_RECONFIRM";
  public static final String NOT_APPLICABLE = "CONSENT_NO_LONGER_ACTIVE";
  public static final String DEADLINE_PASSED = "DEADLINE_PASSED";
  public static final int BATCH_LIMIT = 100;
  static final Duration BASE_DELAY = Duration.ofMinutes(10);
  static final Duration MAX_DELAY = Duration.ofHours(12);

  private static final Logger log = LoggerFactory.getLogger(ConsentNoticeSendService.class);

  public record Result(int sent, int skipped, int retried, int closed) {}

  private final ConsentNoticeBatchRepository repository;
  private final ConsentNoticeMailComposer composer;
  private final MailSender mailSender;
  private final Clock clock;

  public ConsentNoticeSendService(
      ConsentNoticeBatchRepository repository,
      ConsentNoticeMailComposer composer,
      MailSender mailSender,
      Clock clock) {
    this.repository = repository;
    this.composer = composer;
    this.mailSender = mailSender;
    this.clock = clock;
  }

  public static Duration delayAfterFailure(int attemptCountAfterFailure) {
    long factor = 1L << Math.min(Math.max(attemptCountAfterFailure - 1, 0), 10);
    Duration delay = BASE_DELAY.multipliedBy(factor);
    return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
  }

  @Transactional
  public Result sendDue() {
    Instant now = clock.instant();
    int sent = 0;
    int skipped = 0;
    int retried = 0;
    int closed = 0;
    List<DueNotice> due = repository.claimDue(now, BATCH_LIMIT);
    for (DueNotice notice : due) {
      int attempt = notice.attemptCount() + 1;
      if (notice.email() == null || notice.email().isBlank()) {
        repository.markClosed(notice.id(), "SKIPPED_NO_EMAIL", notice.attemptCount(), null);
        skipped++;
        continue;
      }
      if (RECONFIRM.equals(notice.noticeType())
          && (!notice.agreedAtEvent() || notice.latestAgreementId() != notice.termsAgreementId())) {
        repository.markClosed(notice.id(), "FAILED", notice.attemptCount(), NOT_APPLICABLE);
        closed++;
        continue;
      }
      try {
        mailSender.send(message(notice));
        repository.markSent(notice.id(), attempt, now);
        sent++;
      } catch (RuntimeException e) {
        log.warn("수신 설정 통지 발송 실패 noticeId={} attempt={}", notice.id(), attempt);
        Instant next = now.plus(delayAfterFailure(attempt));
        if (!now.isBefore(notice.dueAt())) {
          repository.markClosed(notice.id(), "FAILED", attempt, DEADLINE_PASSED);
          closed++;
        } else {
          Instant target = next.isAfter(notice.dueAt()) ? notice.dueAt() : next;
          repository.reschedule(notice.id(), attempt, target, errorCode(e));
          retried++;
        }
      }
    }
    return new Result(sent, skipped, retried, closed);
  }

  private MailMessage message(DueNotice notice) {
    if (RECONFIRM.equals(notice.noticeType())) {
      return composer.reconfirm(
          notice.email(), notice.userId(), notice.name(), notice.expressedAt());
    }
    return composer.result(
        notice.email(),
        notice.userId(),
        notice.name(),
        notice.expressedAt(),
        notice.agreedAtEvent(),
        "UNSUBSCRIBE_LINK".equals(notice.consentPath()));
  }

  private static String errorCode(RuntimeException e) {
    String name = e.getClass().getSimpleName();
    return name.length() <= 40 ? name : name.substring(0, 40);
  }
}
