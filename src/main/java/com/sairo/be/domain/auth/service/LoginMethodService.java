package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.request.LinkEmailRequest;
import com.sairo.be.domain.auth.dto.response.LoginMethodLinkResponse;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.EmailVerificationPurpose;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.legal.service.KakaoUnlinkEventService;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.global.security.VerifiedTicket;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class LoginMethodService {

  private final AppUserRepository appUserRepository;
  private final SignedTicketService ticketService;
  private final PasswordPolicy passwordPolicy;
  private final PasswordHasher passwordHasher;
  private final StepUpState stepUpState;
  private final KakaoUnlinkEventService kakaoUnlinkEventService;
  private final Clock clock;

  public LoginMethodService(
      AppUserRepository appUserRepository,
      SignedTicketService ticketService,
      PasswordPolicy passwordPolicy,
      PasswordHasher passwordHasher,
      StepUpState stepUpState,
      KakaoUnlinkEventService kakaoUnlinkEventService,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.ticketService = ticketService;
    this.passwordPolicy = passwordPolicy;
    this.passwordHasher = passwordHasher;
    this.stepUpState = stepUpState;
    this.kakaoUnlinkEventService = kakaoUnlinkEventService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public void requireEmailLinkable(Long userId) {
    if (activeUser(userId).hasEmailLogin()) {
      throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
  }

  @Transactional(readOnly = true)
  public void requireKakaoLinkable(Long userId, HttpSession session) {
    stepUpState.require(session);
    if (activeUser(userId).hasKakaoLogin()) {
      throw new BusinessException(ErrorCode.KAKAO_ALREADY_LINKED);
    }
  }

  @Transactional
  public LoginMethodLinkResponse linkEmail(
      Long userId, LinkEmailRequest request, HttpSession session) {
    stepUpState.require(session);
    AppUser user = activeUser(userId);
    passwordPolicy.validateNewPassword("password", request.password());
    VerifiedTicket ticket =
        ticketService.verify(
            request.verifiedEmailTicket(), TicketType.EMAIL, EmailVerificationPurpose.LINK.name());
    if (!userId.equals(ticket.userId())) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (user.hasEmailLogin() || appUserRepository.existsByEmail(ticket.subject())) {
      throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
    try {
      user.linkEmail(ticket.subject(), passwordHasher.hash(request.password()), clock.instant());
      appUserRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
    ticketService.consume(ticket);
    stepUpState.consume(session);
    return new LoginMethodLinkResponse("EMAIL", true);
  }

  @Transactional
  public LoginMethodLinkResponse linkKakao(Long userId, String providerKey, HttpSession session) {
    stepUpState.require(session);
    AppUser user = activeUser(userId);
    if (user.hasKakaoLogin()
        || appUserRepository
            .findByKakaoProviderKey(providerKey)
            .filter(other -> !other.getId().equals(userId))
            .isPresent()) {
      throw new BusinessException(ErrorCode.KAKAO_ALREADY_LINKED);
    }
    try {
      user.linkKakao(providerKey);
      appUserRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.KAKAO_ALREADY_LINKED);
    }
    stepUpState.consume(session);
    return new LoginMethodLinkResponse("KAKAO", true);
  }

  @Transactional
  public void unlink(Long userId, String method, HttpSession session) {
    stepUpState.require(session);
    AppUser user = activeUser(userId);
    switch (method) {
      case "email" -> {
        if (!user.hasEmailLogin()) {
          throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!user.hasKakaoLogin()) {
          throw new BusinessException(ErrorCode.LAST_LOGIN_METHOD);
        }
        user.unlinkEmail();
      }
      case "kakao" -> {
        if (!user.hasKakaoLogin()) {
          throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!user.hasEmailLogin()) {
          throw new BusinessException(ErrorCode.LAST_LOGIN_METHOD);
        }
        kakaoUnlinkEventService.enqueueOutbound(userId, user.getKakaoProviderKey());
        user.unlinkKakao();
      }
      default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    stepUpState.consume(session);
  }

  private AppUser activeUser(Long userId) {
    return appUserRepository
        .findById(userId)
        .filter(AppUser::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }
}
