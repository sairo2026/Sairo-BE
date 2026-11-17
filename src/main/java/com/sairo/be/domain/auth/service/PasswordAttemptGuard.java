package com.sairo.be.domain.auth.service;

import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.RequestRateLimiter.Result;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 이메일 비밀번호 대입 시도를 정규화된 이메일 계정 단위로 세어 5회 실패 시 15분 잠근다. 로그인, 재인증, 권한 재확인이 같은 카운터를 공유하므로 접속 IP를 바꾸거나
 * 다른 경로를 써도 우회할 수 없다. 미가입 이메일도 같은 방식으로 세어 계정 존재 여부가 드러나지 않는다.
 */
@Profile("!migrate")
@Component
public class PasswordAttemptGuard {

  private static final int MAX_ATTEMPTS = AuthRateLimits.LOGIN_FAILURE.limit();

  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public PasswordAttemptGuard(RequestRateLimiter rateLimiter, Clock clock) {
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public void assertNotLocked(String rawEmail) {
    if (rawEmail == null) {
      return;
    }
    Result lock = rateLimiter.peek(AuthRateLimits.LOGIN_LOCK, key(rawEmail));
    if (lock.count() >= 1) {
      throw BusinessException.withDetails(
          ErrorCode.LOGIN_LOCKED, Map.of("retryAfterSeconds", lock.retryAfterSeconds()));
    }
  }

  /** 한 번의 실패 기록 결과다. failedCount는 이번 실패까지 연속 실패 횟수이며 잠금이 걸린 실패에서는 최대 횟수와 같다. */
  public record FailureState(int failedCount, Instant lockedUntil) {

    public int remainingAttempts() {
      return Math.max(0, MAX_ATTEMPTS - failedCount);
    }
  }

  /** 실패를 기록하고, 이번 실패로 잠겼으면 잠금 해제 시각을 돌려준다. 잠기지 않았으면 null이다. */
  public Instant recordFailure(String rawEmail) {
    FailureState state = recordFailureState(rawEmail);
    return state == null ? null : state.lockedUntil();
  }

  /** 실패를 기록하고 현재 연속 실패 횟수와 잠금 해제 시각을 돌려준다. 이메일이 없으면 null이다. */
  public FailureState recordFailureState(String rawEmail) {
    if (rawEmail == null) {
      return null;
    }
    String key = key(rawEmail);
    Result result = rateLimiter.increment(AuthRateLimits.LOGIN_FAILURE, key);
    if (result.count() < MAX_ATTEMPTS) {
      return new FailureState(result.count(), null);
    }
    rateLimiter.increment(AuthRateLimits.LOGIN_LOCK, key);
    rateLimiter.reset(AuthRateLimits.LOGIN_FAILURE, key);
    return new FailureState(MAX_ATTEMPTS, clock.instant().plus(AuthRateLimits.LOGIN_LOCK.window()));
  }

  public static int maxAttempts() {
    return MAX_ATTEMPTS;
  }

  public static long lockMinutes() {
    return AuthRateLimits.LOGIN_LOCK.window().toMinutes();
  }

  public void reset(String rawEmail) {
    if (rawEmail == null) {
      return;
    }
    String key = key(rawEmail);
    rateLimiter.reset(AuthRateLimits.LOGIN_FAILURE, key);
    rateLimiter.reset(AuthRateLimits.LOGIN_LOCK, key);
  }

  private static String key(String rawEmail) {
    return "acct|" + EmailVerificationService.normalizeEmail(rawEmail);
  }
}
