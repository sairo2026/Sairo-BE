package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.visit.dto.response.ApproverAccountResponses.ApproverOtpSendResponse;
import com.sairo.be.domain.visit.repository.VisitApproverPropertyLinkRepository.LinkHead;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.SmsSender;
import com.sairo.be.global.port.SmsSender.SmsMessage;
import com.sairo.be.global.security.SecurityProperties;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.PhoneNumbers;
import com.sairo.be.global.support.PhoneVerificationRequirement;
import com.sairo.be.global.support.PublicLinkTokenGenerator;
import com.sairo.be.global.support.RateLimitRule;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** 승인자 계정의 인증번호 발송과 확인이다. 인증번호의 해시·시도 횟수·발송 시각은 TTL 저장소에 두고 번호와 인증번호 원문은 저장하지 않는다. 문자는 포트로만 보낸다. */
@Profile("!migrate")
@Service
public class ApproverOtpService {

  public enum Purpose {
    CLAIM,
    LOGIN,
    REAUTH
  }

  public record Verified(
      Purpose purpose, Long profileId, boolean profileCreated, Long claimedApprovalId) {}

  public static final Duration CODE_VALIDITY = Duration.ofMinutes(5);
  public static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
  public static final int MAX_ATTEMPTS = 5;
  static final RateLimitRule SEND_INTERVAL =
      RateLimitRule.of("approver-otp-interval", 1, RESEND_INTERVAL);
  static final RateLimitRule SEND_PHONE_HOURLY =
      RateLimitRule.of("approver-otp-phone", 5, Duration.ofHours(1));
  static final RateLimitRule SEND_IP_HOURLY =
      RateLimitRule.of("approver-otp-ip", 20, Duration.ofHours(1));
  static final RateLimitRule VERIFY_IP =
      RateLimitRule.of("approver-verify-ip", 30, Duration.ofMinutes(10));

  private static final Duration RETENTION = Duration.ofMinutes(15);
  private static final String KEY_PREFIX = "sairo:approver-code:";
  private static final Pattern CODE = Pattern.compile("^\\d{6}$");
  private static final Set<String> SEND_KEYS = Set.of("purpose", "phone", "linkToken");
  private static final Set<String> VERIFY_KEYS =
      Set.of("purpose", "phone", "code", "linkToken", "acknowledgedNoticeVersion");

  private final StringRedisTemplate redis;
  private final ObjectProvider<SmsSender> smsSender;
  private final PhoneVerificationRequirement requirement;
  private final RequestRateLimiter rateLimiter;
  private final TokenGenerator tokenGenerator;
  private final PublicLinkTokenGenerator linkTokens;
  private final PublicApproverLinkResolver linkResolver;
  private final ApproverAccountService accountService;
  private final ApproverLinkNoticeProvider noticeProvider;
  private final SecurityProperties properties;
  private final Clock clock;

  public ApproverOtpService(
      StringRedisTemplate redis,
      ObjectProvider<SmsSender> smsSender,
      PhoneVerificationRequirement requirement,
      RequestRateLimiter rateLimiter,
      TokenGenerator tokenGenerator,
      PublicLinkTokenGenerator linkTokens,
      PublicApproverLinkResolver linkResolver,
      ApproverAccountService accountService,
      ApproverLinkNoticeProvider noticeProvider,
      SecurityProperties properties,
      Clock clock) {
    this.redis = redis;
    this.smsSender = smsSender;
    this.requirement = requirement;
    this.rateLimiter = rateLimiter;
    this.tokenGenerator = tokenGenerator;
    this.linkTokens = linkTokens;
    this.linkResolver = linkResolver;
    this.accountService = accountService;
    this.noticeProvider = noticeProvider;
    this.properties = properties;
    this.clock = clock;
  }

  public ApproverOtpSendResponse send(JsonNode body, Long sessionProfileId, String clientIp) {
    requirement.requireSmsAvailable();
    CustomerInput.requireObject(body, SEND_KEYS);
    Purpose purpose = purpose(body);
    String phone = phone(body);
    String token = linkToken(body, purpose);
    if (purpose == Purpose.REAUTH && sessionProfileId == null) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    rateLimiter.hit(SEND_IP_HOURLY, clientIp);
    rateLimiter.hit(SEND_INTERVAL, phone);
    rateLimiter.hit(SEND_PHONE_HOURLY, phone);
    String hash = accountService.phoneHash(phone);
    boolean deliver = true;
    Map<String, String> state = new HashMap<>();
    switch (purpose) {
      case CLAIM -> {
        LinkHead link = linkResolver.resolve(token, clientIp);
        accountService.requireClaimable(link.getApprovalId(), hash);
        state.put("tokenHash", linkTokens.hash(token));
      }
      case LOGIN -> deliver = accountService.loginTargetExists(hash);
      case REAUTH -> {
        accountService.requireReauthTarget(sessionProfileId, hash);
        state.put("profileId", String.valueOf(sessionProfileId));
      }
    }
    if (deliver) {
      String code = tokenGenerator.numericCode(6);
      String key = key(phone, purpose);
      redis.delete(key);
      state.put("codeHash", codeHash(phone, code));
      state.put("attempts", "0");
      state.put("issuedAt", String.valueOf(clock.instant().getEpochSecond()));
      redis.opsForHash().putAll(key, state);
      redis.expire(key, RETENTION);
      smsSender
          .getObject()
          .send(new SmsMessage(phone, "[사이로] 승인자 계정 인증번호는 " + code + "입니다. 5분 안에 입력해 주세요."));
    }
    return new ApproverOtpSendResponse(
        true, (int) CODE_VALIDITY.toSeconds(), (int) RESEND_INTERVAL.toSeconds());
  }

  public Verified verify(JsonNode body, Long sessionProfileId, String clientIp) {
    CustomerInput.requireObject(body, VERIFY_KEYS);
    Purpose purpose = purpose(body);
    String phone = phone(body);
    String code = CustomerInput.requiredText(body, "code", 6, false);
    if (!CODE.matcher(code).matches()) {
      throw CustomerInput.invalid("code", FieldErrorCode.INVALID_FORMAT, "숫자 6자리여야 합니다.");
    }
    String token = linkToken(body, purpose);
    String noticeVersion = null;
    if (purpose == Purpose.CLAIM) {
      noticeVersion = CustomerInput.requiredText(body, "acknowledgedNoticeVersion", 50, false);
    }
    if (purpose == Purpose.REAUTH && sessionProfileId == null) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    rateLimiter.hit(VERIFY_IP, clientIp);
    if (purpose == Purpose.CLAIM
        && !noticeProvider.current().noticeVersion().equals(noticeVersion)) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    String key = key(phone, purpose);
    Map<Object, Object> state = redis.opsForHash().entries(key);
    if (state.isEmpty()) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    Instant issuedAt = Instant.ofEpochSecond(Long.parseLong((String) state.get("issuedAt")));
    if (!issuedAt.plus(CODE_VALIDITY).isAfter(clock.instant())) {
      throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
    }
    if (purpose == Purpose.CLAIM
        && !HmacSupport.constantTimeEquals(
            linkTokens.hash(token), (String) state.get("tokenHash"))) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (purpose == Purpose.REAUTH
        && !String.valueOf(sessionProfileId).equals(state.get("profileId"))) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    String hash = accountService.phoneHash(phone);
    if (!HmacSupport.constantTimeEquals(codeHash(phone, code), (String) state.get("codeHash"))) {
      Long attempts = redis.opsForHash().increment(key, "attempts", 1);
      int used = attempts == null ? MAX_ATTEMPTS : attempts.intValue();
      if (used >= MAX_ATTEMPTS) {
        redis.delete(key);
      }
      if (purpose == Purpose.LOGIN) {
        accountService.recordLoginFailure(hash, clientIp);
      }
      throw BusinessException.verificationCodeInvalid(Math.max(0, MAX_ATTEMPTS - used));
    }
    if (!Boolean.TRUE.equals(redis.delete(key))) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    return switch (purpose) {
      case CLAIM -> {
        LinkHead link = linkResolver.resolve(token, clientIp);
        ApproverAccountService.ClaimResult claimed =
            accountService.claim(phone, link.getApprovalId(), noticeVersion, clientIp);
        yield new Verified(
            purpose, claimed.profileId(), claimed.profileCreated(), claimed.claimedApprovalId());
      }
      case LOGIN -> new Verified(purpose, accountService.login(hash, clientIp), false, null);
      case REAUTH -> {
        accountService.requireReauthTarget(sessionProfileId, hash);
        yield new Verified(purpose, sessionProfileId, false, null);
      }
    };
  }

  private static Purpose purpose(JsonNode body) {
    return CustomerInput.requiredEnum(body, "purpose", Purpose.class);
  }

  private static String phone(JsonNode body) {
    String raw = CustomerInput.requiredText(body, "phone", 30, false);
    return PhoneNumbers.normalize(raw)
        .orElseThrow(
            () ->
                CustomerInput.invalid(
                    "phone", FieldErrorCode.INVALID_FORMAT, "휴대폰 번호 형식이 올바르지 않습니다."));
  }

  private static String linkToken(JsonNode body, Purpose purpose) {
    if (purpose != Purpose.CLAIM) {
      return null;
    }
    return CustomerInput.requiredText(
        body, "linkToken", PublicLinkResolver.TOKEN_MAX_LENGTH, false);
  }

  private String key(String phone, Purpose purpose) {
    return KEY_PREFIX
        + purpose.name()
        + ":"
        + HmacSupport.hmacHex(properties.rateLimitHmacSecret(), "approver-phone|" + phone);
  }

  private String codeHash(String phone, String code) {
    return HmacSupport.hmacHex(properties.ticketSecret(), "approver-code|" + phone + "|" + code);
  }
}
