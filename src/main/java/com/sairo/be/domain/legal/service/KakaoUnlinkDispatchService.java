package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.entity.LegalKakaoUnlinkEvent;
import com.sairo.be.domain.legal.repository.LegalKakaoUnlinkEventRepository;
import com.sairo.be.global.port.KakaoUnlinkPort;
import com.sairo.be.global.support.TextCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Profile("!migrate")
@Service
public class KakaoUnlinkDispatchService {

  public static final Duration KEY_MAX_RETENTION = Duration.ofDays(7);
  public static final String KEY_MAX_RULE = "KAKAO_UNLINK_KEY_MAX";
  public static final int BATCH_SIZE = 50;
  private static final List<Duration> RETRY_DELAYS =
      List.of(
          Duration.ofMinutes(1),
          Duration.ofMinutes(5),
          Duration.ofMinutes(30),
          Duration.ofHours(2),
          Duration.ofHours(12),
          Duration.ofHours(24));
  private static final Duration LATER_DELAY = Duration.ofHours(24);

  private final LegalKakaoUnlinkEventRepository repository;
  private final KakaoUnlinkPort kakaoUnlinkPort;
  private final TextCipher textCipher;
  private final TransactionTemplate transactionTemplate;
  private final RetentionRuleLookup ruleLookup;
  private final Clock clock;

  public KakaoUnlinkDispatchService(
      LegalKakaoUnlinkEventRepository repository,
      KakaoUnlinkPort kakaoUnlinkPort,
      TextCipher textCipher,
      TransactionTemplate transactionTemplate,
      RetentionRuleLookup ruleLookup,
      Clock clock) {
    this.repository = repository;
    this.kakaoUnlinkPort = kakaoUnlinkPort;
    this.textCipher = textCipher;
    this.transactionTemplate = transactionTemplate;
    this.ruleLookup = ruleLookup;
    this.clock = clock;
  }

  public int dispatchDue() {
    abandonExpired();
    List<Long> ids = repository.findDueIds(clock.instant(), PageRequest.of(0, BATCH_SIZE));
    int processed = 0;
    for (Long id : ids) {
      if (dispatch(id)) {
        processed++;
      }
    }
    return processed;
  }

  public static Duration delayAfterFailure(int failedAttempts) {
    if (failedAttempts <= 0) {
      return RETRY_DELAYS.get(0);
    }
    return failedAttempts <= RETRY_DELAYS.size()
        ? RETRY_DELAYS.get(failedAttempts - 1)
        : LATER_DELAY;
  }

  private void abandonExpired() {
    Instant now = clock.instant();
    for (Long id : repository.findExpiredIds(now.minus(keyMaxRetention()))) {
      transactionTemplate.executeWithoutResult(
          status -> repository.findById(id).ifPresent(event -> event.abandon(now)));
      log.warn("카카오 연결 끊기 작업을 보관 상한 초과로 포기했습니다. eventId={}", id);
    }
  }

  /** 식별자 보관 상한이다. 보관 기간 규칙 KAKAO_UNLINK_KEY_MAX를 읽고 규칙이 없으면 문서 확정값 7일을 쓴다. */
  Duration keyMaxRetention() {
    OptionalInt days = ruleLookup.retentionDays(KEY_MAX_RULE);
    return days.isPresent() ? Duration.ofDays(days.getAsInt()) : KEY_MAX_RETENTION;
  }

  private boolean dispatch(Long id) {
    String key =
        transactionTemplate.execute(
            status ->
                repository
                    .findById(id)
                    .map(LegalKakaoUnlinkEvent::getTargetKeyEncrypted)
                    .map(textCipher::decrypt)
                    .orElse(null));
    if (key == null) {
      return false;
    }
    String errorCode = null;
    try {
      kakaoUnlinkPort.unlink(key);
    } catch (RuntimeException e) {
      errorCode = e.getClass().getSimpleName();
      log.warn("카카오 연결 끊기 호출이 실패했습니다. eventId={}, error={}", id, errorCode);
    }
    String failure = errorCode;
    Instant now = clock.instant();
    transactionTemplate.executeWithoutResult(
        status ->
            repository
                .findById(id)
                .ifPresent(
                    event -> {
                      if (failure == null) {
                        event.markSucceeded(now);
                      } else {
                        event.markFailed(
                            now, failure, now.plus(delayAfterFailure(event.getAttemptCount() + 1)));
                      }
                    }));
    return failure == null;
  }
}
