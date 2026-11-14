package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.EmailVerificationPurpose;
import com.sairo.be.domain.auth.entity.EmailVerificationToken;
import com.sairo.be.domain.auth.repository.EmailVerificationTokenRepository;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class EmailVerificationTokenIssuer {

  private final EmailVerificationTokenRepository tokenRepository;
  private final AdvisoryLock advisoryLock;
  private final TokenGenerator tokenGenerator;
  private final Clock clock;

  public EmailVerificationTokenIssuer(
      EmailVerificationTokenRepository tokenRepository,
      AdvisoryLock advisoryLock,
      TokenGenerator tokenGenerator,
      Clock clock) {
    this.tokenRepository = tokenRepository;
    this.advisoryLock = advisoryLock;
    this.tokenGenerator = tokenGenerator;
    this.clock = clock;
  }

  @Transactional
  public String issue(Long userId, String email, EmailVerificationPurpose purpose) {
    advisoryLock.lockForTransaction("email-verification:" + email);
    Instant now = clock.instant();
    tokenRepository.supersedeActive(email, now);
    String token = tokenGenerator.randomToken();
    tokenRepository.saveAndFlush(
        EmailVerificationToken.issue(userId, email, purpose, HmacSupport.sha256Hex(token), now));
    return token;
  }
}
