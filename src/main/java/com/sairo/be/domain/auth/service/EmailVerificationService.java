package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.EmailVerifyResponse;
import com.sairo.be.domain.auth.dto.response.VerificationSendResponse;
import com.sairo.be.domain.auth.entity.EmailVerificationPurpose;
import com.sairo.be.domain.auth.entity.EmailVerificationToken;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.repository.EmailVerificationTokenRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.SignedTicketService.IssuedTicket;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.RequestRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Profile("!migrate")
@Service
public class EmailVerificationService {

  public static final Duration TICKET_VALIDITY = Duration.ofMinutes(10);

  private final AppUserRepository appUserRepository;
  private final EmailVerificationTokenRepository tokenRepository;
  private final EmailVerificationTokenIssuer tokenIssuer;
  private final AuthMailComposer mailComposer;
  private final SignedTicketService ticketService;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public EmailVerificationService(
      AppUserRepository appUserRepository,
      EmailVerificationTokenRepository tokenRepository,
      EmailVerificationTokenIssuer tokenIssuer,
      AuthMailComposer mailComposer,
      SignedTicketService ticketService,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.tokenRepository = tokenRepository;
    this.tokenIssuer = tokenIssuer;
    this.mailComposer = mailComposer;
    this.ticketService = ticketService;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  public static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  public VerificationSendResponse send(
      Long userId, EmailVerificationPurpose purpose, String rawEmail) {
    String email = normalizeEmail(rawEmail);
    rateLimiter.hit(AuthRateLimits.EMAIL_SEND_INTERVAL, email);
    rateLimiter.hit(AuthRateLimits.EMAIL_SEND_HOURLY, email);
    if (appUserRepository.existsByEmail(email)) {
      throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
    String token = tokenIssuer.issue(userId, email, purpose);
    mailComposer.sendVerificationLink(email, purpose, token);
    return new VerificationSendResponse(true, (int) AuthRateLimits.RESEND_INTERVAL.toSeconds());
  }

  public VerificationSendResponse sendLink(Long userId, String rawEmail) {
    return send(userId, EmailVerificationPurpose.LINK, rawEmail);
  }

  @Transactional
  public EmailVerifyResponse verifyLink(Long userId, String token, String clientIp) {
    return verify(userId, EmailVerificationPurpose.LINK, token, clientIp);
  }

  public VerificationSendResponse sendSignup(String rawEmail) {
    return send(null, EmailVerificationPurpose.SIGNUP, rawEmail);
  }

  @Transactional
  public EmailVerifyResponse verifySignup(String token, String clientIp) {
    return verify(null, EmailVerificationPurpose.SIGNUP, token, clientIp);
  }

  @Transactional
  public EmailVerifyResponse verify(
      Long userId, EmailVerificationPurpose purpose, String token, String clientIp) {
    rateLimiter.hit(AuthRateLimits.EMAIL_VERIFY_IP, clientIp);
    EmailVerificationToken stored =
        tokenRepository
            .findByTokenHash(HmacSupport.sha256Hex(token.strip()))
            .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
    if (stored.getPurpose() != purpose
        || (purpose != EmailVerificationPurpose.SIGNUP && !stored.getUserId().equals(userId))) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (!stored.isUsable()) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (stored.isExpiredAt(clock.instant())) {
      throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
    }
    if (tokenRepository.consume(stored.getId(), clock.instant()) != 1) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    IssuedTicket ticket =
        ticketService.issue(
            TicketType.EMAIL,
            stored.getEmail(),
            purpose.name(),
            purpose == EmailVerificationPurpose.SIGNUP ? null : userId,
            TICKET_VALIDITY);
    return new EmailVerifyResponse(ticket.token());
  }
}
