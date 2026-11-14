package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.RecoveryOptionsResponse;
import com.sairo.be.domain.auth.entity.AccountStatus;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.EmailVerificationPurpose;
import com.sairo.be.domain.auth.entity.PhoneVerificationPurpose;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.global.security.VerifiedTicket;
import com.sairo.be.global.support.RequestRateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class RecoveryOptionsService {

  private final SignedTicketService ticketService;
  private final AppUserRepository appUserRepository;
  private final RequestRateLimiter rateLimiter;

  public RecoveryOptionsService(
      SignedTicketService ticketService,
      AppUserRepository appUserRepository,
      RequestRateLimiter rateLimiter) {
    this.ticketService = ticketService;
    this.appUserRepository = appUserRepository;
    this.rateLimiter = rateLimiter;
  }

  public RecoveryOptionsResponse find(
      String verifiedEmailTicket, String verifiedPhoneTicket, String clientIp) {
    rateLimiter.hit(AuthRateLimits.RECOVERY_IP, clientIp);
    boolean hasEmail = verifiedEmailTicket != null && !verifiedEmailTicket.isBlank();
    boolean hasPhone = verifiedPhoneTicket != null && !verifiedPhoneTicket.isBlank();
    if (hasEmail == hasPhone) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "verifiedEmailTicket",
                  FieldErrorCode.INVALID_VALUE,
                  "이메일 인증 티켓과 휴대폰 인증 티켓 중 하나만 보내야 합니다.")));
    }
    Optional<AppUser> account;
    if (hasEmail) {
      VerifiedTicket ticket =
          ticketService.verify(
              verifiedEmailTicket, TicketType.EMAIL, EmailVerificationPurpose.SIGNUP.name());
      account = appUserRepository.findByEmail(ticket.subject()).filter(AppUser::isActive);
    } else {
      VerifiedTicket ticket =
          ticketService.verify(
              verifiedPhoneTicket, TicketType.PHONE, PhoneVerificationPurpose.SIGNUP.name());
      account =
          appUserRepository.findFirstByPhoneAndPhoneVerifiedAtIsNotNullAndAccountStatus(
              ticket.subject(), AccountStatus.ACTIVE);
    }
    if (account.isEmpty()) {
      return new RecoveryOptionsResponse(false, List.of(), false);
    }
    AppUser user = account.get();
    List<String> methods = new ArrayList<>();
    if (user.hasEmailLogin()) {
      methods.add("EMAIL");
    }
    if (user.hasKakaoLogin()) {
      methods.add("KAKAO");
    }
    return new RecoveryOptionsResponse(true, methods, user.hasEmailLogin());
  }
}
