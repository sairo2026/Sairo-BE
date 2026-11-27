package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerLinkRepository;
import com.sairo.be.domain.visit.repository.VisitCustomerTimeSlotRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.MailSender;
import com.sairo.be.global.security.SecurityProperties;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.global.support.PublicLinkTokenGenerator;
import com.sairo.be.global.support.RateLimitRule;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A customer link plus an email OTP grants read-only access to that office's requests. */
@Profile("!migrate")
@Service
public class CustomerVisitPortalService {

  public record SendResult(boolean accepted, String maskedEmail, int expiresInSeconds) {}

  public record PortalItem(
      Long coordinationId, String status, LocalDate periodStartDate, LocalDate periodEndDate) {}

  public record PortalRequests(boolean available, boolean verified, List<PortalItem> items) {}

  public record TimeSlot(OffsetDateTime startsAt, OffsetDateTime endsAt) {}

  public record ReusableTimes(List<TimeSlot> timeSlots) {}

  private record Scope(Long officeId, Long customerId, String email, Long linkId) {}

  private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
  private static final Duration CODE_TTL = Duration.ofMinutes(5);
  public static final Duration SESSION_TTL = Duration.ofDays(30);
  private static final int MAX_ATTEMPTS = 5;
  private static final String OTP_PREFIX = "sairo:customer-portal-otp:";
  private static final String SESSION_PREFIX = "sairo:customer-portal-session:";
  private static final String SESSION_LINK_PREFIX = "sairo:customer-portal-session-link:";
  private static final RateLimitRule SEND_IP =
      RateLimitRule.of("customer-portal-send-ip", 12, Duration.ofHours(1));
  private static final RateLimitRule SEND_LINK =
      RateLimitRule.of("customer-portal-send-link", 4, Duration.ofHours(1));
  private static final RateLimitRule SEND_INTERVAL =
      RateLimitRule.of("customer-portal-send-interval", 1, Duration.ofMinutes(1));
  private static final RateLimitRule VERIFY_IP =
      RateLimitRule.of("customer-portal-verify-ip", 20, Duration.ofMinutes(10));

  private final PublicLinkResolver resolver;
  private final VisitCoordinationRepository coordinations;
  private final VisitCustomerLinkRepository links;
  private final VisitCustomerTimeSlotRepository slots;
  private final CustomerRepository customers;
  private final StringRedisTemplate redis;
  private final MailSender mail;
  private final RequestRateLimiter rateLimiter;
  private final TokenGenerator tokens;
  private final PublicLinkTokenGenerator linkTokens;
  private final SecurityProperties properties;
  private final Clock clock;

  public CustomerVisitPortalService(
      PublicLinkResolver resolver,
      VisitCoordinationRepository coordinations,
      VisitCustomerLinkRepository links,
      VisitCustomerTimeSlotRepository slots,
      CustomerRepository customers,
      StringRedisTemplate redis,
      MailSender mail,
      RequestRateLimiter rateLimiter,
      TokenGenerator tokens,
      PublicLinkTokenGenerator linkTokens,
      SecurityProperties properties,
      Clock clock) {
    this.resolver = resolver;
    this.coordinations = coordinations;
    this.links = links;
    this.slots = slots;
    this.customers = customers;
    this.redis = redis;
    this.mail = mail;
    this.rateLimiter = rateLimiter;
    this.tokens = tokens;
    this.linkTokens = linkTokens;
    this.properties = properties;
    this.clock = clock;
  }

  public SendResult sendCode(String linkToken, String clientIp) {
    Scope scope = scope(linkToken, clientIp, true);
    if (scope.email() == null) throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    rateLimiter.hit(SEND_IP, clientIp);
    String recipientKey = scope.officeId() + ":" + scope.customerId();
    rateLimiter.hit(SEND_INTERVAL, recipientKey);
    rateLimiter.hit(SEND_LINK, recipientKey);
    String code = tokens.numericCode(6);
    String key = otpKey(linkToken);
    redis.delete(key);
    redis
        .opsForHash()
        .putAll(
            key,
            Map.of(
                "codeHash", codeHash(linkToken, code),
                "emailHash", emailHash(scope.email()),
                "attempts", "0",
                "issuedAt", String.valueOf(clock.instant().getEpochSecond())));
    redis.expire(key, CODE_TTL);
    try {
      mail.send(
          new MailSender.MailMessage(
              scope.email(),
              "SAIRO visit verification code",
              "Your SAIRO visit verification code: " + code + "\nValid for 5 minutes."));
    } catch (RuntimeException e) {
      redis.delete(key);
      throw e;
    }
    int at = scope.email().indexOf('@');
    String masked = scope.email().substring(0, 1) + "***" + scope.email().substring(at);
    return new SendResult(true, masked, (int) CODE_TTL.toSeconds());
  }

  @Transactional(readOnly = true)
  public String verifyCode(String linkToken, String code, String clientIp) {
    Scope scope = scope(linkToken, clientIp, true);
    if (scope.email() == null) throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    if (code == null || !CODE.matcher(code).matches()) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    rateLimiter.hit(VERIFY_IP, clientIp);
    String key = otpKey(linkToken);
    Map<Object, Object> state = redis.opsForHash().entries(key);
    if (state.isEmpty()) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (!HmacSupport.constantTimeEquals(
        emailHash(scope.email()), (String) state.get("emailHash"))) {
      redis.delete(key);
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    Instant issued = Instant.ofEpochSecond(Long.parseLong((String) state.get("issuedAt")));
    if (!issued.plus(CODE_TTL).isAfter(clock.instant())) {
      redis.delete(key);
      throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
    }
    Long attempts = redis.opsForHash().increment(key, "attempts", 1);
    if (attempts == null || attempts > MAX_ATTEMPTS) {
      redis.delete(key);
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (!HmacSupport.constantTimeEquals(
        codeHash(linkToken, code), (String) state.get("codeHash"))) {
      if (attempts >= MAX_ATTEMPTS) redis.delete(key);
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    if (!Boolean.TRUE.equals(redis.delete(key))) {
      throw new BusinessException(ErrorCode.TOKEN_INVALID);
    }
    String sessionToken = tokens.randomToken();
    redis
        .opsForValue()
        .set(
            sessionKey(sessionToken),
            scope.officeId() + ":" + scope.customerId() + ":" + emailHash(scope.email()),
            SESSION_TTL);
    try {
      redis.opsForValue().set(sessionLinkKey(sessionToken), scope.linkId().toString(), SESSION_TTL);
    } catch (RuntimeException e) {
      redis.delete(sessionKey(sessionToken));
      throw e;
    }
    return sessionToken;
  }

  @Transactional(readOnly = true)
  public PortalRequests requests(String linkToken, String sessionToken, String clientIp) {
    Scope scope = authenticatedScope(linkToken, sessionToken, clientIp);
    return new PortalRequests(true, true, itemsFor(scope));
  }

  /** The verified customer can return to this read-only index without finding the original link. */
  @Transactional(readOnly = true)
  public PortalRequests sessionRequests(String sessionToken) {
    Scope scope = sessionScope(sessionToken);
    return new PortalRequests(true, true, itemsFor(scope));
  }

  private List<PortalItem> itemsFor(Scope scope) {
    return coordinations
        .findTop50ByOfficeIdAndCustomerIdOrderByIdDesc(scope.officeId(), scope.customerId())
        .stream()
        .map(
            c ->
                new PortalItem(
                    c.getId(), c.getStatus().name(), c.getPeriodStartDate(), c.getPeriodEndDate()))
        .toList();
  }

  @Transactional(readOnly = true)
  public boolean canVerify(String linkToken, String clientIp) {
    return scope(linkToken, clientIp, false).email() != null;
  }

  @Transactional(readOnly = true)
  public ReusableTimes reusableTimes(String linkToken, String sessionToken, String clientIp) {
    Scope scope = authenticatedScope(linkToken, sessionToken, clientIp);
    VisitCustomerLinkRepository.LinkHead current = resolver.resolve(linkToken, clientIp);
    if (!clock.instant().isBefore(current.getExpiresAt())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    VisitCoordination target =
        coordinations
            .findById(current.getCoordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!target.getOfficeId().equals(scope.officeId())
        || !target.getCustomerId().equals(scope.customerId())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    if (!target.getStatus().acceptsCustomerResponse()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    Instant start = TimeRules.startOfDisplayDay(target.getPeriodStartDate());
    Instant end = TimeRules.endOfDisplayDayExclusive(target.getPeriodEndDate());
    for (VisitCoordination previous :
        coordinations.findTop50ByOfficeIdAndCustomerIdOrderByIdDesc(
            scope.officeId(), scope.customerId())) {
      if (previous.getId().equals(target.getId())) continue;
      for (VisitCustomerLink link : links.findByCoordinationIdOrderByIdDesc(previous.getId())) {
        if (link.getResponseVersion() == 0
            || link.isRevoked()
            || link.getRetentionUntil()
                .isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) {
          continue;
        }
        List<TimeSlot> reusable =
            slots
                .findByCustomerLinkIdAndResponseVersionOrderByStartsAtAscIdAsc(
                    link.getId(), link.getResponseVersion())
                .stream()
                .filter(slot -> slot.getStartsAt().isAfter(now))
                .filter(
                    slot -> !slot.getStartsAt().isBefore(start) && !slot.getEndsAt().isAfter(end))
                .map(
                    slot ->
                        new TimeSlot(
                            TimeRules.toDisplayOffset(slot.getStartsAt()),
                            TimeRules.toDisplayOffset(slot.getEndsAt())))
                .toList();
        if (!reusable.isEmpty()) return new ReusableTimes(reusable);
      }
    }
    return new ReusableTimes(List.of());
  }

  private Scope authenticatedScope(String linkToken, String sessionToken, String clientIp) {
    Scope scope = scope(linkToken, clientIp, false);
    if (scope.email() == null
        || sessionToken == null
        || sessionToken.isBlank()
        || sessionToken.length() > 128) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    String stored = redis.opsForValue().get(sessionKey(sessionToken));
    String expected = scope.officeId() + ":" + scope.customerId() + ":" + emailHash(scope.email());
    if (!HmacSupport.constantTimeEquals(expected, stored)) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return scope;
  }

  public void logout(String sessionToken) {
    if (sessionToken != null && !sessionToken.isBlank() && sessionToken.length() <= 128) {
      redis.delete(sessionKey(sessionToken));
      redis.delete(sessionLinkKey(sessionToken));
    }
  }

  private Scope sessionScope(String sessionToken) {
    if (sessionToken == null || sessionToken.isBlank() || sessionToken.length() > 128) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    String value = redis.opsForValue().get(sessionKey(sessionToken));
    String linkId = redis.opsForValue().get(sessionLinkKey(sessionToken));
    if (value == null || linkId == null) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    VisitCustomerLink link;
    try {
      link = links.findById(Long.parseLong(linkId)).orElse(null);
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    if (link == null || link.isRevoked() || link.getRetentionUntil().isBefore(today)) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    VisitCoordination coordination =
        coordinations
            .findById(link.getCoordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    Customer customer =
        customers
            .findByIdAndOfficeId(coordination.getCustomerId(), coordination.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (customer.isArchived() || customer.getRetentionUntil().isBefore(today)) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    String email = customer.getEmail();
    if (email == null || !email.contains("@")) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    String canonicalEmail = email.trim().toLowerCase(java.util.Locale.ROOT);
    String expected =
        coordination.getOfficeId()
            + ":"
            + coordination.getCustomerId()
            + ":"
            + emailHash(canonicalEmail);
    if (!HmacSupport.constantTimeEquals(expected, value)) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return new Scope(
        coordination.getOfficeId(), coordination.getCustomerId(), canonicalEmail, link.getId());
  }

  private Scope scope(String linkToken, String clientIp, boolean requireUnexpired) {
    VisitCustomerLinkRepository.LinkHead link = resolver.resolve(linkToken, clientIp);
    if (requireUnexpired && !clock.instant().isBefore(link.getExpiresAt())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    VisitCoordination coordination =
        coordinations
            .findById(link.getCoordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    Customer customer =
        customers
            .findByIdAndOfficeId(coordination.getCustomerId(), coordination.getOfficeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    String email = customer.getEmail();
    if (customer.isArchived()
        || customer
            .getRetentionUntil()
            .isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return new Scope(
        coordination.getOfficeId(),
        coordination.getCustomerId(),
        email != null && email.contains("@")
            ? email.trim().toLowerCase(java.util.Locale.ROOT)
            : null,
        link.getId());
  }

  private String otpKey(String linkToken) {
    return OTP_PREFIX + linkTokens.hash(linkToken);
  }

  private String sessionKey(String sessionToken) {
    return SESSION_PREFIX + linkTokens.hash(sessionToken);
  }

  private String sessionLinkKey(String sessionToken) {
    return SESSION_LINK_PREFIX + linkTokens.hash(sessionToken);
  }

  private String codeHash(String linkToken, String code) {
    return HmacSupport.hmacHex(
        properties.ticketSecret(), "customer-portal|" + linkToken + "|" + code);
  }

  private String emailHash(String email) {
    return HmacSupport.hmacHex(properties.ticketSecret(), "customer-portal-email|" + email);
  }
}
