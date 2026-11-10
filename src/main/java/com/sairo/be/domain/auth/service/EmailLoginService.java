package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.AuthMethod;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.RequestRateLimiter.Result;
import com.sairo.be.global.web.ClientRequestInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class EmailLoginService {

  private final AppUserRepository appUserRepository;
  private final PasswordHasher passwordHasher;
  private final RequestRateLimiter rateLimiter;
  private final LoginHistoryService loginHistoryService;
  private final MemberPrincipalFactory principalFactory;
  private final Clock clock;

  public EmailLoginService(
      AppUserRepository appUserRepository,
      PasswordHasher passwordHasher,
      RequestRateLimiter rateLimiter,
      LoginHistoryService loginHistoryService,
      MemberPrincipalFactory principalFactory,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.passwordHasher = passwordHasher;
    this.rateLimiter = rateLimiter;
    this.loginHistoryService = loginHistoryService;
    this.principalFactory = principalFactory;
    this.clock = clock;
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public StaffPrincipal login(String rawEmail, String password, ClientRequestInfo client) {
    String email = EmailVerificationService.normalizeEmail(rawEmail);
    rateLimiter.hit(AuthRateLimits.LOGIN_IP, client.ipAddress());
    String failureKey = client.ipAddress() + "|" + email;
    Result lock = rateLimiter.peek(AuthRateLimits.LOGIN_LOCK, failureKey);
    if (lock.count() >= 1) {
      throw lockedException(lock.retryAfterSeconds());
    }

    Optional<AppUser> found =
        appUserRepository
            .findByEmail(email)
            .filter(user -> user.isActive() && user.hasEmailLogin());
    if (found.isEmpty()) {
      passwordHasher.spendComparisonTime(password);
      recordFailure(failureKey, null);
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    AppUser user = found.get();
    if (!passwordHasher.matches(password, user.getPasswordHash())) {
      recordFailure(failureKey, user);
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    rateLimiter.reset(AuthRateLimits.LOGIN_FAILURE, failureKey);
    rateLimiter.reset(AuthRateLimits.LOGIN_LOCK, failureKey);
    user.recordLoginSuccess();
    loginHistoryService.record(user.getId(), AuthMethod.EMAIL, client);
    return principalFactory.create(user);
  }

  private void recordFailure(String failureKey, AppUser user) {
    Result result = rateLimiter.increment(AuthRateLimits.LOGIN_FAILURE, failureKey);
    boolean locks = result.count() >= AuthRateLimits.LOGIN_FAILURE.limit();
    Instant lockedUntil = null;
    if (locks) {
      rateLimiter.increment(AuthRateLimits.LOGIN_LOCK, failureKey);
      rateLimiter.reset(AuthRateLimits.LOGIN_FAILURE, failureKey);
      lockedUntil = clock.instant().plus(AuthRateLimits.LOGIN_LOCK.window());
    }
    if (user != null) {
      user.recordLoginFailure(user.getFailedLoginCount() + 1, lockedUntil);
    }
  }

  private BusinessException lockedException(long retryAfterSeconds) {
    return BusinessException.withDetails(
        ErrorCode.LOGIN_LOCKED, java.util.Map.of("retryAfterSeconds", retryAfterSeconds));
  }
}
