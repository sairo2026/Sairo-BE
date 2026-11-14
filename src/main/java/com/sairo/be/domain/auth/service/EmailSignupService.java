package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.request.SignupEmailRequest;
import com.sairo.be.domain.auth.entity.AccountStatus;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.AuthMethod;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.EmailVerificationPurpose;
import com.sairo.be.domain.auth.entity.PhoneVerificationPurpose;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.global.security.VerifiedTicket;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.web.ClientRequestInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class EmailSignupService {

  public static final int DISPLAY_NAME_MAX = 50;

  private final AppUserRepository appUserRepository;
  private final SignedTicketService ticketService;
  private final PasswordPolicy passwordPolicy;
  private final PasswordHasher passwordHasher;
  private final TermsAgreementService termsAgreementService;
  private final LoginHistoryService loginHistoryService;
  private final MemberPrincipalFactory principalFactory;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public EmailSignupService(
      AppUserRepository appUserRepository,
      SignedTicketService ticketService,
      PasswordPolicy passwordPolicy,
      PasswordHasher passwordHasher,
      TermsAgreementService termsAgreementService,
      LoginHistoryService loginHistoryService,
      MemberPrincipalFactory principalFactory,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.ticketService = ticketService;
    this.passwordPolicy = passwordPolicy;
    this.passwordHasher = passwordHasher;
    this.termsAgreementService = termsAgreementService;
    this.loginHistoryService = loginHistoryService;
    this.principalFactory = principalFactory;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public StaffPrincipal signup(SignupEmailRequest request, ClientRequestInfo client) {
    rateLimiter.hit(AuthRateLimits.SIGNUP_IP, client.ipAddress());
    String displayName =
        InputRules.requireText("displayName", request.displayName(), 1, DISPLAY_NAME_MAX);
    passwordPolicy.validateNewPassword("password", request.password());

    VerifiedTicket emailTicket =
        ticketService.verify(
            request.verifiedEmailTicket(),
            TicketType.EMAIL,
            EmailVerificationPurpose.SIGNUP.name());
    VerifiedTicket phoneTicket =
        ticketService.verify(
            request.verifiedPhoneTicket(),
            TicketType.PHONE,
            PhoneVerificationPurpose.SIGNUP.name());
    String email = emailTicket.subject();
    String phone = phoneTicket.subject();

    if (appUserRepository.existsByEmail(email)) {
      throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
    if (appUserRepository.existsByPhoneAndPhoneVerifiedAtIsNotNullAndAccountStatus(
        phone, AccountStatus.ACTIVE)) {
      throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
    }
    List<TermsVersionView> accepted =
        termsAgreementService.validateSignupAgreements(request.agreedTerms(), true);

    Instant now = clock.instant();
    AppUser user;
    try {
      user =
          appUserRepository.saveAndFlush(
              AppUser.registerWithEmail(
                  email, passwordHasher.hash(request.password()), displayName, phone, now, now));
    } catch (DataIntegrityViolationException e) {
      throw duplicateOf(e);
    }
    ticketService.consume(emailTicket);
    ticketService.consume(phoneTicket);
    termsAgreementService.recordSignupAgreements(
        user.getId(), accepted, ConsentPath.SIGNUP_EMAIL, now, true);
    loginHistoryService.record(user.getId(), AuthMethod.EMAIL, client);
    return principalFactory.create(user);
  }

  private BusinessException duplicateOf(DataIntegrityViolationException e) {
    String message = e.getMostSpecificCause().getMessage();
    if (message != null && message.contains("uq_app_user_verified_phone")) {
      return new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
    }
    return new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
  }
}
