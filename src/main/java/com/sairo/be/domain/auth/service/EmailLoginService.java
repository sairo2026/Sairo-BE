package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.AuthMethod;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.web.ClientRequestInfo;
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
  private final PasswordAttemptGuard attemptGuard;

  public EmailLoginService(
      AppUserRepository appUserRepository,
      PasswordHasher passwordHasher,
      RequestRateLimiter rateLimiter,
      LoginHistoryService loginHistoryService,
      MemberPrincipalFactory principalFactory,
      PasswordAttemptGuard attemptGuard) {
    this.appUserRepository = appUserRepository;
    this.passwordHasher = passwordHasher;
    this.rateLimiter = rateLimiter;
    this.loginHistoryService = loginHistoryService;
    this.principalFactory = principalFactory;
    this.attemptGuard = attemptGuard;
  }

  @Transactional(noRollbackFor = BusinessException.class)
  public StaffPrincipal login(String rawEmail, String password, ClientRequestInfo client) {
    String email = EmailVerificationService.normalizeEmail(rawEmail);
    rateLimiter.hit(AuthRateLimits.LOGIN_IP, client.ipAddress());
    attemptGuard.assertNotLocked(email);

    Optional<AppUser> found =
        appUserRepository
            .findByEmail(email)
            .filter(user -> user.isActive() && user.hasEmailLogin());
    if (found.isEmpty()) {
      passwordHasher.spendComparisonTime(password);
      throw invalidCredentials(recordFailure(email, null));
    }
    AppUser user = found.get();
    if (!passwordHasher.matches(password, user.getPasswordHash())) {
      throw invalidCredentials(recordFailure(email, user));
    }
    attemptGuard.reset(email);
    user.recordLoginSuccess();
    loginHistoryService.record(user.getId(), AuthMethod.EMAIL, client);
    return principalFactory.create(user);
  }

  private PasswordAttemptGuard.FailureState recordFailure(String email, AppUser user) {
    PasswordAttemptGuard.FailureState state = attemptGuard.recordFailureState(email);
    if (user != null) {
      user.recordLoginFailure(user.getFailedLoginCount() + 1, state.lockedUntil());
    }
    return state;
  }

  private static BusinessException invalidCredentials(PasswordAttemptGuard.FailureState state) {
    return BusinessException.invalidCredentials(
        state.failedCount(),
        state.remainingAttempts(),
        PasswordAttemptGuard.maxAttempts(),
        PasswordAttemptGuard.lockMinutes());
  }
}
