package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.PhoneVerificationPurpose;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.global.security.VerifiedTicket;
import com.sairo.be.global.support.PhoneNumbers;
import com.sairo.be.global.support.PhoneVerificationRequirement;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
public class SignupPhoneResolver {

  private final PhoneVerificationRequirement requirement;
  private final SignedTicketService ticketService;

  public SignupPhoneResolver(
      PhoneVerificationRequirement requirement, SignedTicketService ticketService) {
    this.requirement = requirement;
    this.ticketService = ticketService;
  }

  public ResolvedPhone resolve(String rawPhone, String ticketToken) {
    if (requirement.isRequired()) {
      if (ticketToken == null || ticketToken.isBlank()) {
        throw fieldError("verifiedPhoneTicket", FieldErrorCode.REQUIRED, "휴대폰 본인확인 티켓이 필요합니다.");
      }
      VerifiedTicket ticket =
          ticketService.verify(
              ticketToken, TicketType.PHONE, PhoneVerificationPurpose.SIGNUP.name());
      return new ResolvedPhone(ticket.subject(), ticket);
    }
    if (rawPhone == null || rawPhone.isBlank()) {
      throw fieldError("phone", FieldErrorCode.REQUIRED, "휴대폰 번호를 입력해 주세요.");
    }
    return new ResolvedPhone(normalize(rawPhone), null);
  }

  public void consume(ResolvedPhone phone) {
    if (phone.verified()) {
      ticketService.consume(phone.ticket());
    }
  }

  public String normalize(String rawPhone) {
    return PhoneNumbers.normalize(rawPhone)
        .orElseThrow(
            () -> fieldError("phone", FieldErrorCode.INVALID_FORMAT, "휴대폰 번호 형식이 올바르지 않습니다."));
  }

  private static BusinessException fieldError(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }

  public record ResolvedPhone(String value, VerifiedTicket ticket) {

    public boolean verified() {
      return ticket != null;
    }
  }
}
