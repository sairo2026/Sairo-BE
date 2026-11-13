package com.sairo.be.domain.home.service;

import com.sairo.be.domain.home.repository.NotificationDeliveryRepository;
import com.sairo.be.domain.home.repository.NotificationDeliveryRepository.DueRow;
import com.sairo.be.global.port.MailSender;
import com.sairo.be.global.port.MailSender.MailMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림의 이메일 전달을 맡는다. 전달 대기 행을 읽어 보내고 실패하면 5분, 30분, 2시간 뒤에 다시 시도한다. 세 번의 재시도가 모두 실패하면 전달 포기로 두고 시스템 작업
 * 실패 행을 남긴다.
 */
@Profile("!migrate")
@Service
public class NotificationDeliveryService {

  static final String EMAIL = "EMAIL";
  static final int BATCH_LIMIT = 100;
  static final List<Duration> RETRY_DELAYS =
      List.of(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));
  static final String SUBJECT_PREFIX = "[사이로] ";
  static final String FOOTER = "\n\n이 메일은 서비스 이용에 필요한 안내입니다. 알림함에서도 같은 내용을 볼 수 있습니다.";

  private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

  private final NotificationDeliveryRepository repository;
  private final MailSender mailSender;
  private final Clock clock;

  public NotificationDeliveryService(
      NotificationDeliveryRepository repository, MailSender mailSender, Clock clock) {
    this.repository = repository;
    this.mailSender = mailSender;
    this.clock = clock;
  }

  /** 실패 뒤 시도 횟수에 따른 다음 시도까지의 간격이다. 재시도를 모두 썼으면 빈 값이다. */
  static Optional<Duration> delayAfterFailure(int attemptCountAfterFailure) {
    int index = attemptCountAfterFailure - 1;
    return index >= 0 && index < RETRY_DELAYS.size()
        ? Optional.of(RETRY_DELAYS.get(index))
        : Optional.empty();
  }

  static String subjectOf(String message) {
    String firstLine = message.lines().findFirst().orElse(message).strip();
    String title = firstLine.length() <= 60 ? firstLine : firstLine.substring(0, 60);
    return SUBJECT_PREFIX + title;
  }

  @Transactional
  public int deliverDue() {
    Instant now = clock.instant();
    List<DueRow> due = repository.claimDue(now, BATCH_LIMIT);
    for (DueRow row : due) {
      deliver(row, now);
    }
    return due.size();
  }

  private void deliver(DueRow row, Instant now) {
    int attempt = row.attemptCount() + 1;
    Optional<String> email = repository.recipientEmail(row.recipientUserId());
    if (email.isEmpty() || email.get().isBlank()) {
      repository.markTerminal(row.id(), "FAILED", attempt, now, "NO_RECIPIENT_EMAIL");
      return;
    }
    try {
      mailSender.send(
          new MailMessage(email.get(), subjectOf(row.message()), row.message() + FOOTER));
      repository.markDelivered(row.id(), attempt, now);
    } catch (RuntimeException e) {
      log.warn("알림 이메일 전달 실패 deliveryId={} attempt={}", row.id(), attempt);
      String code = errorCode(e);
      Optional<Duration> delay = delayAfterFailure(attempt);
      if (delay.isPresent()) {
        repository.markRetry(row.id(), attempt, now, now.plus(delay.get()), code);
      } else {
        repository.markTerminal(row.id(), "ABANDONED", attempt, now, code);
        repository.recordAbandoned(row.id(), now, "DELIVERY_ABANDONED");
      }
    }
  }

  private static String errorCode(RuntimeException e) {
    String name = e.getClass().getSimpleName();
    return name.length() <= 80 ? name : name.substring(0, 80);
  }
}
