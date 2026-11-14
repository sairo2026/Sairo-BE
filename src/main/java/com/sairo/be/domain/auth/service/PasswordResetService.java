package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.PasswordResetLinkResponse;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.PasswordResetToken;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.repository.PasswordResetTokenRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SessionTerminator;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.RequestRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PasswordResetService {

  private final AppUserRepository appUserRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final PasswordResetTokenIssuer tokenIssuer;
  private final PasswordPolicy passwordPolicy;
  private final PasswordHasher passwordHasher;
  private final SessionTerminator sessionTerminator;
  private final AuthMailComposer mailComposer;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public PasswordResetService(
      AppUserRepository appUserRepository,
      PasswordResetTokenRepository tokenRepository,
      PasswordResetTokenIssuer tokenIssuer,
      PasswordPolicy passwordPolicy,
      PasswordHasher passwordHasher,
      SessionTerminator sessionTerminator,
      AuthMailComposer mailComposer,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.tokenRepository = tokenRepository;
    this.tokenIssuer = tokenIssuer;
    this.passwordPolicy = passwordPolicy;
    this.passwordHasher = passwordHasher;
    this.sessionTerminator = sessionTerminator;
    this.mailComposer = mailComposer;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public PasswordResetLinkResponse requestReset(String rawEmail, String clientIp) {
    String email = EmailVerificationService.normalizeEmail(rawEmail);
    rateLimiter.hit(AuthRateLimits.PASSWORD_RESET_IP, clientIp);
    rateLimiter.hit(AuthRateLimits.PASSWORD_RESET_INTERVAL, email);
    rateLimiter.hit(AuthRateLimits.PASSWORD_RESET_HOURLY, email);
    Optional<String> token = tokenIssuer.issueToken(email);
    token.ifPresent(value -> mailComposer.sendPasswordResetLink(email, value));
    return new PasswordResetLinkResponse(true);
  }

  @Transactional
  public void reset(String token, String newPassword, String clientIp) {
    rateLimiter.hit(AuthRateLimits.PASSWORD_RESET_IP, clientIp);
    passwordPolicy.validateNewPassword("newPassword", newPassword);
    PasswordResetToken stored =
        tokenRepository
            .findByTokenHash(HmacSupport.sha256Hex(token.strip()))
            .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
    if (!stored.isUsable()) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    Instant now = clock.instant();
    if (stored.isExpiredAt(now)) {
      throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
    }
    if (tokenRepository.consume(stored.getId(), now) != 1) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    AppUser user =
        appUserRepository
            .findById(stored.getUserId())
            .filter(AppUser::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
    user.changePassword(passwordHasher.hash(newPassword));
    sessionTerminator.terminateAll(user.getId());
  }
}
