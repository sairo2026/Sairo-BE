package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.PasswordResetToken;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.repository.PasswordResetTokenRepository;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class PasswordResetTokenIssuer {

  private final AppUserRepository appUserRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final AdvisoryLock advisoryLock;
  private final TokenGenerator tokenGenerator;
  private final Clock clock;

  public PasswordResetTokenIssuer(
      AppUserRepository appUserRepository,
      PasswordResetTokenRepository tokenRepository,
      AdvisoryLock advisoryLock,
      TokenGenerator tokenGenerator,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.tokenRepository = tokenRepository;
    this.advisoryLock = advisoryLock;
    this.tokenGenerator = tokenGenerator;
    this.clock = clock;
  }

  @Transactional
  public Optional<String> issueToken(String email) {
    Optional<AppUser> user =
        appUserRepository
            .findByEmail(email)
            .filter(found -> found.isActive() && found.hasEmailLogin());
    if (user.isEmpty()) {
      return Optional.empty();
    }
    advisoryLock.lockForTransaction("password-reset:" + user.get().getId());
    Instant now = clock.instant();
    tokenRepository.supersedeActive(user.get().getId(), now);
    String token = tokenGenerator.randomToken();
    tokenRepository.saveAndFlush(
        PasswordResetToken.issue(user.get().getId(), HmacSupport.sha256Hex(token), now));
    return Optional.of(token);
  }
}
