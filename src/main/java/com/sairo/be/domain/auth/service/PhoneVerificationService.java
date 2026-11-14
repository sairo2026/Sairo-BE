package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.PhoneVerifyResponse;
import com.sairo.be.domain.auth.dto.response.VerificationSendResponse;
import com.sairo.be.domain.auth.entity.PhoneVerificationPurpose;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.SmsSender;
import com.sairo.be.global.port.SmsSender.SmsMessage;
import com.sairo.be.global.security.SecurityProperties;
import com.sairo.be.global.security.SignedTicketService;
import com.sairo.be.global.security.SignedTicketService.IssuedTicket;
import com.sairo.be.global.security.TicketType;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.PhoneNumbers;
import com.sairo.be.global.support.PhoneVerificationRequirement;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Profile("!migrate")
@Service
public class PhoneVerificationService {

  public static final Duration CODE_VALIDITY = Duration.ofMinutes(5);
  public static final Duration TICKET_VALIDITY = Duration.ofMinutes(10);
  public static final int MAX_ATTEMPTS = 5;
  private static final Duration RETENTION = Duration.ofMinutes(15);
  private static final String KEY_PREFIX = "sairo:phone-code:";
  private static final String FIELD_CODE_HASH = "codeHash";
  private static final String FIELD_ATTEMPTS = "attempts";
  private static final String FIELD_ISSUED_AT = "issuedAt";
  private static final String FIELD_USER_ID = "userId";

  private final StringRedisTemplate redis;
  private final ObjectProvider<SmsSender> smsSender;
  private final PhoneVerificationRequirement requirement;
  private final SignedTicketService ticketService;
  private final RequestRateLimiter rateLimiter;
  private final TokenGenerator tokenGenerator;
  private final SecurityProperties properties;
  private final Clock clock;

  public PhoneVerificationService(
      StringRedisTemplate redis,
      ObjectProvider<SmsSender> smsSender,
      PhoneVerificationRequirement requirement,
      SignedTicketService ticketService,
      RequestRateLimiter rateLimiter,
      TokenGenerator tokenGenerator,
      SecurityProperties properties,
      Clock clock) {
    this.redis = redis;
    this.smsSender = smsSender;
    this.requirement = requirement;
    this.ticketService = ticketService;
    this.rateLimiter = rateLimiter;
    this.tokenGenerator = tokenGenerator;
    this.properties = properties;
    this.clock = clock;
  }

  public VerificationSendResponse send(
      Long currentUserId, String rawPhone, PhoneVerificationPurpose purpose, String clientIp) {
    requirement.requireSmsAvailable();
    String phone = requirePhone(rawPhone);
    requireLoginForChange(currentUserId, purpose);
    rateLimiter.hit(AuthRateLimits.PHONE_SEND_IP_HOURLY, clientIp);
    rateLimiter.hit(AuthRateLimits.PHONE_SEND_INTERVAL, phone);
    rateLimiter.hit(AuthRateLimits.PHONE_SEND_HOURLY, phone);

    String code = tokenGenerator.numericCode(6);
    String key = key(phone, purpose);
    redis.delete(key);
    Map<String, String> state = new HashMap<>();
    state.put(FIELD_CODE_HASH, codeHash(phone, code));
    state.put(FIELD_ATTEMPTS, "0");
    state.put(FIELD_ISSUED_AT, String.valueOf(clock.instant().getEpochSecond()));
    if (purpose == PhoneVerificationPurpose.CHANGE) {
      state.put(FIELD_USER_ID, String.valueOf(currentUserId));
    }
    redis.opsForHash().putAll(key, state);
    redis.expire(key, RETENTION);
    smsSender
        .getObject()
        .send(new SmsMessage(phone, "[사이로] 인증번호는 " + code + "입니다. 5분 안에 입력해 주세요."));
    return new VerificationSendResponse(true, (int) AuthRateLimits.RESEND_INTERVAL.toSeconds());
  }

  public PhoneVerifyResponse verify(
      Long currentUserId,
      String rawPhone,
      PhoneVerificationPurpose purpose,
      String code,
      String clientIp) {
    String phone = requirePhone(rawPhone);
    requireLoginForChange(currentUserId, purpose);
    rateLimiter.hit(AuthRateLimits.PHONE_VERIFY_IP, clientIp);

    String key = key(phone, purpose);
    Map<Object, Object> state = redis.opsForHash().entries(key);
    if (state.isEmpty()) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    Instant issuedAt = Instant.ofEpochSecond(Long.parseLong((String) state.get(FIELD_ISSUED_AT)));
    if (!issuedAt.plus(CODE_VALIDITY).isAfter(clock.instant())) {
      throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
    }
    if (purpose == PhoneVerificationPurpose.CHANGE
        && !String.valueOf(currentUserId).equals(state.get(FIELD_USER_ID))) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (!HmacSupport.constantTimeEquals(
        codeHash(phone, code), (String) state.get(FIELD_CODE_HASH))) {
      Long attempts = redis.opsForHash().increment(key, FIELD_ATTEMPTS, 1);
      int used = attempts == null ? MAX_ATTEMPTS : attempts.intValue();
      if (used >= MAX_ATTEMPTS) {
        redis.delete(key);
      }
      throw BusinessException.verificationCodeInvalid(Math.max(0, MAX_ATTEMPTS - used));
    }
    if (!Boolean.TRUE.equals(redis.delete(key))) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    IssuedTicket ticket =
        ticketService.issue(
            TicketType.PHONE,
            phone,
            purpose.name(),
            purpose == PhoneVerificationPurpose.CHANGE ? currentUserId : null,
            TICKET_VALIDITY);
    return new PhoneVerifyResponse(
        ticket.token(), TimeRules.toDisplay(ticket.expiresAt()).toOffsetDateTime());
  }

  private String requirePhone(String rawPhone) {
    return PhoneNumbers.normalize(rawPhone)
        .orElseThrow(
            () ->
                BusinessException.withFieldErrors(
                    ErrorCode.INVALID_REQUEST,
                    List.of(
                        new FieldErrorItem(
                            "phone", FieldErrorCode.INVALID_FORMAT, "휴대폰 번호 형식이 올바르지 않습니다."))));
  }

  private void requireLoginForChange(Long currentUserId, PhoneVerificationPurpose purpose) {
    if (purpose == PhoneVerificationPurpose.CHANGE && currentUserId == null) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
  }

  private String key(String phone, PhoneVerificationPurpose purpose) {
    return KEY_PREFIX
        + purpose.name()
        + ":"
        + HmacSupport.hmacHex(properties.rateLimitHmacSecret(), "phone|" + phone);
  }

  private String codeHash(String phone, String code) {
    return HmacSupport.hmacHex(properties.ticketSecret(), "phone-code|" + phone + "|" + code);
  }
}
