package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.request.SignupKakaoRequest;
import com.sairo.be.domain.auth.entity.AccountStatus;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.ConsentPath;
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
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class KakaoSignupService {

  private final AppUserRepository appUserRepository;
  private final SignedTicketService ticketService;
  private final TermsAgreementService termsAgreementService;
  private final MemberPrincipalFactory principalFactory;
  private final Clock clock;

  public KakaoSignupService(
      AppUserRepository appUserRepository,
      SignedTicketService ticketService,
      TermsAgreementService termsAgreementService,
      MemberPrincipalFactory principalFactory,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.ticketService = ticketService;
    this.termsAgreementService = termsAgreementService;
    this.principalFactory = principalFactory;
    this.clock = clock;
  }

  @Transactional
  public StaffPrincipal complete(Long userId, SignupKakaoRequest request) {
    AppUser user =
        appUserRepository
            .findById(userId)
            .filter(AppUser::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    if (!user.hasKakaoLogin() || user.isSignupCompleted()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    String displayName =
        InputRules.requireText(
            "displayName", request.displayName(), 1, EmailSignupService.DISPLAY_NAME_MAX);
    String referenceEmail =
        request.kakaoReferenceEmail() == null || request.kakaoReferenceEmail().isBlank()
            ? null
            : EmailVerificationService.normalizeEmail(request.kakaoReferenceEmail());

    VerifiedTicket phoneTicket =
        ticketService.verify(
            request.verifiedPhoneTicket(),
            TicketType.PHONE,
            PhoneVerificationPurpose.SIGNUP.name());
    String phone = phoneTicket.subject();
    if (appUserRepository.existsByPhoneAndPhoneVerifiedAtIsNotNullAndAccountStatus(
        phone, AccountStatus.ACTIVE)) {
      throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
    }
    List<TermsVersionView> accepted =
        termsAgreementService.validateSignupAgreements(request.agreedTerms(), false);

    Instant now = clock.instant();
    user.completeKakaoSignup(displayName, phone, now, referenceEmail);
    try {
      appUserRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
    }
    ticketService.consume(phoneTicket);
    termsAgreementService.recordSignupAgreements(
        user.getId(), accepted, ConsentPath.SIGNUP_KAKAO, now, false);
    return principalFactory.create(user);
  }
}
