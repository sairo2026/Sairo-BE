package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.request.ContactChangeRequest;
import com.sairo.be.domain.auth.dto.response.ContactChangeResponse;
import com.sairo.be.domain.auth.dto.response.EmailVerifyResponse;
import com.sairo.be.domain.auth.dto.response.VerificationSendResponse;
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
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.global.security.VerifiedTicket;
import com.sairo.be.global.support.PhoneNumbers;
import com.sairo.be.global.support.PhoneVerificationRequirement;
import com.sairo.be.global.support.TimeRules;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Profile("!migrate")
@Service
public class ContactChangeService {

  private final AppUserRepository appUserRepository;
  private final EmailVerificationService emailVerificationService;
  private final SignedTicketService ticketService;
  private final SignupPhoneResolver phoneResolver;
  private final PhoneVerificationRequirement phoneRequirement;
  private final StepUpState stepUpState;
  private final AuthMailComposer mailComposer;
  private final Clock clock;

  public ContactChangeService(
      AppUserRepository appUserRepository,
      EmailVerificationService emailVerificationService,
      SignedTicketService ticketService,
      SignupPhoneResolver phoneResolver,
      PhoneVerificationRequirement phoneRequirement,
      StepUpState stepUpState,
      AuthMailComposer mailComposer,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.emailVerificationService = emailVerificationService;
    this.ticketService = ticketService;
    this.phoneResolver = phoneResolver;
    this.phoneRequirement = phoneRequirement;
    this.stepUpState = stepUpState;
    this.mailComposer = mailComposer;
    this.clock = clock;
  }

  public VerificationSendResponse sendEmailVerification(
      Long userId, String email, HttpSession session) {
    requireEmailChangeable(userId, session);
    return emailVerificationService.send(userId, EmailVerificationPurpose.CHANGE, email);
  }

  @Transactional
  public EmailVerifyResponse verifyEmail(
      Long userId, String token, String clientIp, HttpSession session) {
    stepUpState.require(session);
    return emailVerificationService.verify(
        userId, EmailVerificationPurpose.CHANGE, token, clientIp);
  }

  @Transactional
  public ContactChangeResponse change(
      Long userId, ContactChangeRequest request, HttpSession session) {
    boolean hasEmail = request.email() != null && !request.email().isBlank();
    boolean hasPhone = request.phone() != null && !request.phone().isBlank();
    if (hasEmail == hasPhone) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "email", FieldErrorCode.INVALID_VALUE, "email과 phone 중 하나만 보내야 합니다.")));
    }
    ChangeResult result =
        hasEmail
            ? changeEmail(userId, request.email(), request.verifiedTicket(), session)
            : changePhone(userId, request.phone(), request.verifiedTicket());
    String previousEmail = result.previousEmailToNotify();
    if (previousEmail != null) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              mailComposer.sendNotice(
                  previousEmail,
                  "[사이로] 로그인 이메일이 변경되었습니다",
                  "계정의 로그인 이메일이 변경되었습니다. 본인이 아니라면 즉시 고객지원에 문의해 주세요.");
            }
          });
    }
    return result.response();
  }

  private ChangeResult changeEmail(
      Long userId, String rawEmail, String ticketToken, HttpSession session) {
    return doChangeEmail(
        userId, EmailVerificationService.normalizeEmail(rawEmail), ticketToken, session);
  }

  private ChangeResult doChangeEmail(
      Long userId, String email, String ticketToken, HttpSession session) {
    requireTicketToken(ticketToken);
    AppUser user = requireEmailChangeable(userId, session);
    VerifiedTicket ticket =
        ticketService.verify(ticketToken, TicketType.EMAIL, EmailVerificationPurpose.CHANGE.name());
    if (!userId.equals(ticket.userId()) || !email.equals(ticket.subject())) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (appUserRepository.existsByEmail(email)) {
      throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
    String previous = user.getEmail();
    Instant now = clock.instant();
    try {
      user.changeEmail(email, now);
      appUserRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }
    ticketService.consume(ticket);
    stepUpState.consume(session);
    return new ChangeResult(
        new ContactChangeResponse("email", email, TimeRules.toDisplay(now).toOffsetDateTime()),
        previous);
  }

  private ChangeResult changePhone(Long userId, String rawPhone, String ticketToken) {
    String phone =
        PhoneNumbers.normalize(rawPhone)
            .orElseThrow(
                () ->
                    BusinessException.withFieldErrors(
                        ErrorCode.INVALID_REQUEST,
                        List.of(
                            new FieldErrorItem(
                                "phone", FieldErrorCode.INVALID_FORMAT, "휴대폰 번호 형식이 올바르지 않습니다."))));
    AppUser user = activeUser(userId);
    boolean verifying = phoneRequirement.isRequired();
    VerifiedTicket ticket = null;
    if (verifying) {
      requireTicketToken(ticketToken);
      ticket =
          ticketService.verify(
              ticketToken, TicketType.PHONE, PhoneVerificationPurpose.CHANGE.name());
      if (!userId.equals(ticket.userId()) || !phone.equals(ticket.subject())) {
        throw new BusinessException(ErrorCode.TOKEN_INVALID);
      }
      boolean takenByOther =
          appUserRepository
              .findFirstByPhoneAndPhoneVerifiedAtIsNotNullAndAccountStatus(
                  phone, AccountStatus.ACTIVE)
              .filter(other -> !other.getId().equals(userId))
              .isPresent();
      if (takenByOther) {
        throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
      }
    }
    Instant now = clock.instant();
    try {
      user.changePhone(phone, verifying ? now : null);
      appUserRepository.flush();
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.PHONE_ALREADY_REGISTERED);
    }
    if (ticket != null) {
      ticketService.consume(ticket);
    }
    return new ChangeResult(
        new ContactChangeResponse("phone", phone, TimeRules.toDisplay(now).toOffsetDateTime()),
        null);
  }

  private static void requireTicketToken(String ticketToken) {
    if (ticketToken == null || ticketToken.isBlank()) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(new FieldErrorItem("verifiedTicket", FieldErrorCode.REQUIRED, "인증 티켓이 필요합니다.")));
    }
  }

  private AppUser requireEmailChangeable(Long userId, HttpSession session) {
    stepUpState.require(session);
    AppUser user = activeUser(userId);
    if (!user.hasEmailLogin()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    return user;
  }

  private AppUser activeUser(Long userId) {
    return appUserRepository
        .findById(userId)
        .filter(AppUser::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }

  private record ChangeResult(ContactChangeResponse response, String previousEmailToNotify) {}
}
