package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.entity.Customer;
import com.sairo.be.domain.customer.repository.CustomerRepository;
import com.sairo.be.domain.visit.dto.response.ConfirmedScheduleResponses.PublicConfirmedVisitResponse;
import com.sairo.be.domain.visit.dto.response.CustomerAvailableTimesResponse;
import com.sairo.be.domain.visit.dto.response.PublicVisitLinkResponse;
import com.sairo.be.domain.visit.entity.ConsentGuestNoticeAck;
import com.sairo.be.domain.visit.entity.CustomerVisitPreference;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import com.sairo.be.domain.visit.repository.ConsentGuestNoticeAckRepository;
import com.sairo.be.domain.visit.repository.CustomerVisitPreferenceRepository;
import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** A customer link plus an email OTP grants read-only access to that office's requests. */
@Profile("!migrate")
@Service
public class CustomerVisitPortalService {

  public record SendResult(boolean accepted, String maskedEmail, int expiresInSeconds) {}

  public record PortalItem(
      Long coordinationId,
      String status,
      LocalDate periodStartDate,
      LocalDate periodEndDate,
      boolean canRespond,
      boolean canViewSchedule) {}

  public record PortalRequests(boolean available, boolean verified, List<PortalItem> items) {}

  public record TimeSlot(OffsetDateTime startsAt, OffsetDateTime endsAt) {}

  public record ReusableTimes(List<TimeSlot> timeSlots) {}

  public record PreferenceInput(
      Long expectedVersion,
      List<DayOfWeek> preferredWeekdays,
      LocalTime preferredStartTime,
      LocalTime preferredEndTime,
      String acknowledgedNoticeVersion,
      String acknowledgedPreferenceDisclosureVersion,
      String sessionBinding) {}

  public record PreferenceData(
      boolean exists,
      long version,
      List<DayOfWeek> preferredWeekdays,
      LocalTime preferredStartTime,
      LocalTime preferredEndTime,
      Instant confirmedAt,
      LocalDate retentionUntil,
      String noticeVersion,
      String preferenceDisclosureVersion,
      String preferenceDisclosureText,
      String sessionBinding) {}

  private record Scope(Long officeId, Long customerId, String email, Long linkId) {}

  private static final Pattern CODE = Pattern.compile("^[0-9]{6}$");
  public static final String PREFERENCE_DISCLOSURE_VERSION = CustomerPreferenceNotice.VERSION;
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
  private final CustomerVisitPreferenceRepository preferences;
  private final ConsentGuestNoticeAckRepository noticeAcks;
  private final CustomerLinkNoticeProvider noticeProvider;
  private final ScheduleItemRepository scheduleItems;
  private final CustomerRepository customers;
  private final PublicCustomerLinkQueryService queryService;
  private final PublicCustomerLinkSubmitService submitService;
  private final PublicConfirmedScheduleService confirmedService;
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
      CustomerVisitPreferenceRepository preferences,
      ConsentGuestNoticeAckRepository noticeAcks,
      CustomerLinkNoticeProvider noticeProvider,
      ScheduleItemRepository scheduleItems,
      CustomerRepository customers,
      PublicCustomerLinkQueryService queryService,
      PublicCustomerLinkSubmitService submitService,
      PublicConfirmedScheduleService confirmedService,
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
    this.preferences = preferences;
    this.noticeAcks = noticeAcks;
    this.noticeProvider = noticeProvider;
    this.scheduleItems = scheduleItems;
    this.customers = customers;
    this.queryService = queryService;
    this.submitService = submitService;
    this.confirmedService = confirmedService;
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

  @Transactional(readOnly = true)
  public PreferenceData preference(String sessionToken) {
    return preferenceFor(sessionScope(sessionToken), sessionToken);
  }

  @Transactional(readOnly = true)
  public PreferenceData preferenceForLink(String linkToken, String sessionToken, String clientIp) {
    var link = resolver.resolve(linkToken, clientIp);
    if (!clock.instant().isBefore(link.getExpiresAt())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_EXPIRED);
    }
    return preferenceFor(authenticatedScope(linkToken, sessionToken, clientIp), sessionToken);
  }

  private PreferenceData preferenceFor(Scope scope, String sessionToken) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    LocalDate retentionUntil = preferenceRetention(scope);
    return preferences
        .findByOfficeIdAndCustomerId(scope.officeId(), scope.customerId())
        .filter(saved -> !saved.getRetentionUntil().isBefore(today))
        .map(saved -> preferenceData(saved, customerRetention(scope), sessionToken))
        .orElseGet(
            () ->
                new PreferenceData(
                    false,
                    0,
                    List.of(),
                    null,
                    null,
                    null,
                    retentionUntil,
                    noticeProvider.current().noticeVersion(),
                    PREFERENCE_DISCLOSURE_VERSION,
                    CustomerPreferenceNotice.TEXT,
                    sessionBinding(sessionToken)));
  }

  @Transactional
  public PreferenceData savePreference(String sessionToken, PreferenceInput input) {
    Scope scope = sessionScope(sessionToken);
    if (!HmacSupport.constantTimeEquals(
        sessionBinding(sessionToken), input == null ? null : input.sessionBinding())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    if (input == null || input.expectedVersion() == null || input.expectedVersion() < 0) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    var notice = noticeProvider.current();
    if (!notice.noticeVersion().equals(input.acknowledgedNoticeVersion())) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    if (!PREFERENCE_DISCLOSURE_VERSION.equals(input.acknowledgedPreferenceDisclosureVersion())) {
      throw new BusinessException(ErrorCode.NOTICE_VERSION_CHANGED);
    }
    if (input.preferredWeekdays() != null && input.preferredWeekdays().contains(null)) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    List<DayOfWeek> weekdays =
        input.preferredWeekdays() == null
            ? List.of()
            : input.preferredWeekdays().stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    if (weekdays.size() > 7
        || (input.preferredStartTime() == null) != (input.preferredEndTime() == null)
        || (input.preferredStartTime() != null
            && !input.preferredStartTime().isBefore(input.preferredEndTime()))
        || (weekdays.isEmpty() && input.preferredStartTime() == null)) {
      throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
    Customer customer = lockCustomer(scope);
    CustomerVisitPreference preference =
        preferences.findForUpdate(scope.officeId(), scope.customerId()).orElse(null);
    boolean expired =
        preference != null
            && preference
                .getRetentionUntil()
                .isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
    long actualVersion = preference == null || expired ? 0 : preference.getVersion();
    if (actualVersion != input.expectedVersion()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (preference == null)
      preference = CustomerVisitPreference.create(scope.officeId(), scope.customerId());
    LocalDate saveRetention =
        actualVersion == 0
            ? preferenceRetention(scope, customer)
            : earlier(preference.getRetentionUntil(), customer.getRetentionUntil());
    preference.confirm(
        String.join(",", weekdays.stream().map(DayOfWeek::name).toList()),
        input.preferredStartTime(),
        input.preferredEndTime(),
        saveRetention,
        clock.instant());
    CustomerVisitPreference saved = preferences.saveAndFlush(preference);
    noticeAcks.saveAndFlush(
        ConsentGuestNoticeAck.customerPreferenceSave(
            scope.officeId(),
            scope.linkId(),
            notice.noticeCode(),
            notice.noticeVersion(),
            PREFERENCE_DISCLOSURE_VERSION,
            CustomerPreferenceNotice.SHA256,
            Math.toIntExact(saved.getVersion()),
            clock.instant()));
    return preferenceData(saved, customer.getRetentionUntil(), sessionToken);
  }

  @Transactional
  public PreferenceData deletePreference(
      String sessionToken, long expectedVersion, String expectedSessionBinding) {
    Scope scope = sessionScope(sessionToken);
    if (!HmacSupport.constantTimeEquals(sessionBinding(sessionToken), expectedSessionBinding)) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    Customer customer = lockCustomer(scope);
    LocalDate retentionUntil = preferenceRetention(scope, customer);
    CustomerVisitPreference preference =
        preferences.findForUpdate(scope.officeId(), scope.customerId()).orElse(null);
    if (preference == null
        || preference
            .getRetentionUntil()
            .isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) {
      if (expectedVersion != 0) throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      return new PreferenceData(
          false,
          0,
          List.of(),
          null,
          null,
          null,
          retentionUntil,
          noticeProvider.current().noticeVersion(),
          PREFERENCE_DISCLOSURE_VERSION,
          CustomerPreferenceNotice.TEXT,
          sessionBinding(sessionToken));
    }
    if (preference.getVersion() != expectedVersion) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    preference.deactivate(clock.instant());
    return preferenceData(
        preferences.saveAndFlush(preference), customer.getRetentionUntil(), sessionToken);
  }

  private PreferenceData preferenceData(
      CustomerVisitPreference preference, LocalDate currentRetention, String sessionToken) {
    String days = preference.getPreferredWeekdays();
    LocalDate retentionUntil =
        preference.getRetentionUntil().isBefore(currentRetention)
            ? preference.getRetentionUntil()
            : currentRetention;
    return new PreferenceData(
        preference.isActive(),
        preference.getVersion(),
        !preference.isActive() || days == null || days.isEmpty()
            ? List.of()
            : java.util.Arrays.stream(days.split(",")).map(DayOfWeek::valueOf).toList(),
        preference.isActive() ? preference.getPreferredStartTime() : null,
        preference.isActive() ? preference.getPreferredEndTime() : null,
        preference.isActive() ? preference.getConfirmedAt() : null,
        retentionUntil,
        noticeProvider.current().noticeVersion(),
        PREFERENCE_DISCLOSURE_VERSION,
        CustomerPreferenceNotice.TEXT,
        sessionBinding(sessionToken));
  }

  private String sessionBinding(String sessionToken) {
    return HmacSupport.hmacHex(
        properties.ticketSecret(), "customer-preference-session|" + sessionToken);
  }

  private static LocalDate earlier(LocalDate left, LocalDate right) {
    return left.isBefore(right) ? left : right;
  }

  private Customer lockCustomer(Scope scope) {
    Customer customer =
        customers
            .findForUpdate(scope.customerId(), scope.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    String email = customer.getEmail();
    if (customer.isArchived()
        || customer
            .getRetentionUntil()
            .isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))
        || email == null
        || !HmacSupport.constantTimeEquals(
            emailHash(scope.email()), emailHash(email.trim().toLowerCase(java.util.Locale.ROOT)))) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return customer;
  }

  private LocalDate preferenceRetention(Scope scope) {
    Customer customer =
        customers
            .findByIdAndOfficeId(scope.customerId(), scope.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return preferenceRetention(scope, customer);
  }

  private LocalDate customerRetention(Scope scope) {
    Customer customer =
        customers
            .findByIdAndOfficeId(scope.customerId(), scope.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return customer.getRetentionUntil();
  }

  private LocalDate preferenceRetention(Scope scope, Customer customer) {
    LocalDate linkRetention =
        links
            .findById(scope.linkId())
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND))
            .getRetentionUntil();
    return linkRetention.isBefore(customer.getRetentionUntil())
        ? linkRetention
        : customer.getRetentionUntil();
  }

  @Transactional(readOnly = true)
  public PublicVisitLinkResponse sessionRequest(String sessionToken, Long coordinationId) {
    Scope scope = sessionScope(sessionToken);
    return queryService.getForVerifiedCustomer(respondableLink(scope, coordinationId));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PublicConfirmedVisitResponse sessionConfirmed(String sessionToken, Long coordinationId) {
    Scope scope = sessionScope(sessionToken);
    if (coordinationId == null) throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    VisitCoordination coordination =
        coordinations
            .findById(coordinationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!coordination.getOfficeId().equals(scope.officeId())
        || !coordination.getCustomerId().equals(scope.customerId())) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    VisitCustomerLink link =
        links.findByCoordinationIdOrderByIdDesc(coordinationId).stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (link.getRetentionUntil().isBefore(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))))) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return confirmedService.verifiedCustomer(coordination, link.isRevoked());
  }

  public CustomerAvailableTimesResponse sessionSubmit(
      String sessionToken, Long coordinationId, JsonNode body, String idempotencyKey) {
    Scope scope = sessionScope(sessionToken);
    VisitCustomerLink link = respondableLink(scope, coordinationId);
    return submitService.submitForVerifiedCustomer(
        link.getId(), scope.officeId(), scope.customerId(), body, idempotencyKey);
  }

  private List<PortalItem> itemsFor(Scope scope) {
    List<VisitCoordination> current =
        coordinations.findTop50ByOfficeIdAndCustomerIdOrderByIdDesc(
            scope.officeId(), scope.customerId());
    if (current.isEmpty()) return List.of();
    Set<Long> activeLinkCoordinationIds =
        new HashSet<>(
            links.findActiveCoordinationIds(
                current.stream().map(VisitCoordination::getId).toList(), clock.instant()));
    Set<Long> viewableScheduleIds =
        new HashSet<>(
            scheduleItems.viewableCustomerCoordinationIds(
                current.stream().map(VisitCoordination::getId).toList(),
                clock.instant().minus(PublicConfirmedScheduleService.CUSTOMER_VIEW_AFTER_LAST),
                LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))));
    return current.stream()
        .map(
            c ->
                new PortalItem(
                    c.getId(),
                    c.getStatus().name(),
                    c.getPeriodStartDate(),
                    c.getPeriodEndDate(),
                    c.getStatus().acceptsCustomerResponse()
                        && activeLinkCoordinationIds.contains(c.getId()),
                    viewableScheduleIds.contains(c.getId())))
        .toList();
  }

  private VisitCustomerLink respondableLink(Scope scope, Long coordinationId) {
    if (coordinationId == null) throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    VisitCoordination coordination =
        coordinations
            .findById(coordinationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    if (!coordination.getOfficeId().equals(scope.officeId())
        || !coordination.getCustomerId().equals(scope.customerId())
        || !coordination.getStatus().acceptsCustomerResponse()) {
      throw new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND);
    }
    return links.findByCoordinationIdOrderByIdDesc(coordinationId).stream()
        .filter(link -> !link.isRevoked() && !link.isExpiredAt(clock.instant()))
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
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
    return reusableTimesForTarget(scope, target);
  }

  @Transactional(readOnly = true)
  public ReusableTimes sessionReusableTimes(String sessionToken, Long coordinationId) {
    Scope scope = sessionScope(sessionToken);
    respondableLink(scope, coordinationId);
    VisitCoordination target =
        coordinations
            .findById(coordinationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PUBLIC_LINK_NOT_FOUND));
    return reusableTimesForTarget(scope, target);
  }

  private ReusableTimes reusableTimesForTarget(Scope scope, VisitCoordination target) {
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
