package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.StepUpResponse;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.RateLimitRule;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.RequestRateLimiter.Result;
import com.sairo.be.global.support.TimeRules;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class StepUpService {

  private final AppUserRepository appUserRepository;
  private final PasswordHasher passwordHasher;
  private final StepUpState stepUpState;
  private final RequestRateLimiter rateLimiter;

  public StepUpService(
      AppUserRepository appUserRepository,
      PasswordHasher passwordHasher,
      StepUpState stepUpState,
      RequestRateLimiter rateLimiter) {
    this.appUserRepository = appUserRepository;
    this.passwordHasher = passwordHasher;
    this.stepUpState = stepUpState;
    this.rateLimiter = rateLimiter;
  }

  @Transactional(readOnly = true)
  public StepUpResponse withPassword(Long userId, String password, HttpSession session) {
    AppUser user = activeUser(userId);
    RateLimitRule lock = AuthRateLimits.STEP_UP_LOCK;
    String key = "stepup|" + userId;
    Result locked = rateLimiter.peek(lock, key);
    if (locked.count() >= 1) {
      throw BusinessException.withDetails(
          ErrorCode.LOGIN_LOCKED, Map.of("retryAfterSeconds", locked.retryAfterSeconds()));
    }
    if (!user.hasEmailLogin()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (!passwordHasher.matches(password, user.getPasswordHash())) {
      Result failures = rateLimiter.increment(AuthRateLimits.STEP_UP_FAILURE, key);
      if (failures.count() >= AuthRateLimits.STEP_UP_FAILURE.limit()) {
        rateLimiter.increment(lock, key);
        rateLimiter.reset(AuthRateLimits.STEP_UP_FAILURE, key);
      }
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    rateLimiter.reset(AuthRateLimits.STEP_UP_FAILURE, key);
    return granted(session);
  }

  @Transactional(readOnly = true)
  public void requireKakaoStepUpAllowed(Long userId) {
    if (!activeUser(userId).hasKakaoLogin()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  @Transactional(readOnly = true)
  public StepUpResponse withKakao(Long userId, String providerKey, HttpSession session) {
    AppUser user = activeUser(userId);
    if (!user.hasKakaoLogin() || !providerKey.equals(user.getKakaoProviderKey())) {
      throw new BusinessException(ErrorCode.STEP_UP_ACCOUNT_MISMATCH);
    }
    return granted(session);
  }

  private StepUpResponse granted(HttpSession session) {
    Instant until = stepUpState.grant(session);
    return new StepUpResponse(true, TimeRules.toDisplay(until).toOffsetDateTime());
  }

  private AppUser activeUser(Long userId) {
    return appUserRepository
        .findById(userId)
        .filter(AppUser::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }
}
