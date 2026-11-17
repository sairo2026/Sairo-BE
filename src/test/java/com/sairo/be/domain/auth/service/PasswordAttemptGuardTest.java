package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.RequestRateLimiter.Result;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PasswordAttemptGuardTest {

  private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");

  private final RequestRateLimiter limiter = mock(RequestRateLimiter.class);
  private final PasswordAttemptGuard guard =
      new PasswordAttemptGuard(limiter, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void 잠기기_전_실패는_연속_횟수와_남은_횟수를_돌려준다() {
    when(limiter.increment(eq(AuthRateLimits.LOGIN_FAILURE), any())).thenReturn(new Result(2, 800));

    PasswordAttemptGuard.FailureState state = guard.recordFailureState("a@example.com");

    assertThat(state.failedCount()).isEqualTo(2);
    assertThat(state.remainingAttempts()).isEqualTo(3);
    assertThat(state.lockedUntil()).isNull();
    verify(limiter, never()).increment(eq(AuthRateLimits.LOGIN_LOCK), any());
  }

  @Test
  void 다섯_번째_실패는_남은_횟수_0으로_잠금을_건다() {
    when(limiter.increment(eq(AuthRateLimits.LOGIN_FAILURE), any())).thenReturn(new Result(5, 600));

    PasswordAttemptGuard.FailureState state = guard.recordFailureState("a@example.com");

    assertThat(state.failedCount()).isEqualTo(5);
    assertThat(state.remainingAttempts()).isZero();
    assertThat(state.lockedUntil()).isEqualTo(NOW.plusSeconds(15 * 60));
    verify(limiter).increment(eq(AuthRateLimits.LOGIN_LOCK), any());
    verify(limiter).reset(eq(AuthRateLimits.LOGIN_FAILURE), any());
  }

  @Test
  void 최대_횟수와_잠금_시간은_규칙에서_가져온다() {
    assertThat(PasswordAttemptGuard.maxAttempts()).isEqualTo(5);
    assertThat(PasswordAttemptGuard.lockMinutes()).isEqualTo(15);
  }

  @Test
  void 이메일이_없으면_기록하지_않는다() {
    assertThat(guard.recordFailureState(null)).isNull();
    assertThat(guard.recordFailure(null)).isNull();
  }
}
