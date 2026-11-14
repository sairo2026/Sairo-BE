package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.request.OpsTrialPriceRequests.BusinessCalendarUpdateRequest;
import com.sairo.be.domain.ops.dto.request.OpsTrialPriceRequests.PriceScheduleCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsTrialPriceRequests.TrialEventCreateRequest;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.BusinessCalendarItem;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.BusinessCalendarResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.DuplicateOffice;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.PriceScheduleApproveResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.PriceScheduleCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.PriceScheduleItem;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.PriceScheduleListResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventApproveResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventItem;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventListResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventPreviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsTrialPriceResponses.TrialEventStopResponse;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository.EventFilter;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository.EventRow;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository.NewEvent;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository.NewPrice;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository.OfficeRef;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository.PriceFilter;
import com.sairo.be.domain.ops.repository.OpsTrialPriceRepository.PriceRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요금표 버전 등록·승인, 체험 이벤트 등록·미리보기·승인·중단, 영업일 달력이다(OPS-08). 승인은 작성자와 다른 운영자만 하고(자기 승인 403 → DB 역할 재확인
 * 순), 효력·시작 시각이 지났으면 바로 시행·활성이고 아니면 예약이다. 예약 상태를 시행·활성으로 바꾸는 일은 billing의 시행 전환 배치가 맡는다.
 */
@Profile("!migrate")
@Service
public class OpsTrialPriceService {

  static final Set<String> STATUSES = Set.of("DRAFT", "REVIEW", "SCHEDULED", "ACTIVE", "ENDED");
  static final Set<String> PRICE_AUDIENCES = Set.of("STANDARD", "START_CUSTOMER");
  static final Set<String> EVENT_AUDIENCES = Set.of("GENERAL", "START_CUSTOMER");
  static final Set<String> FREE_END_MODES = Set.of("DURATION", "FIXED_END");
  static final Duration NOTICE_LEAD = Duration.ofDays(30);
  static final int CALENDAR_RANGE_DAYS = 366;

  private final OpsTrialPriceRepository repository;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final AdvisoryLock advisoryLock;
  private final OperatorStepUpState stepUp;
  private final Clock clock;

  public OpsTrialPriceService(
      OpsTrialPriceRepository repository,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      AdvisoryLock advisoryLock,
      OperatorStepUpState stepUp,
      Clock clock) {
    this.repository = repository;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.advisoryLock = advisoryLock;
    this.stepUp = stepUp;
    this.clock = clock;
  }

  // 요금표

  @Transactional(readOnly = true)
  public PriceScheduleListResponse prices(String status, String audience, PageQuery page) {
    PriceFilter filter =
        new PriceFilter(
            OpsQueryParams.oneOf("status", status, STATUSES),
            OpsQueryParams.oneOf("audience", audience, PRICE_AUDIENCES));
    List<PriceScheduleItem> items =
        repository.prices(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsTrialPriceService::toItem)
            .toList();
    return new PriceScheduleListResponse(
        items, page.page(), page.size(), repository.countPrices(filter));
  }

  @Transactional
  public PriceScheduleCreateResponse createPrice(long operatorId, PriceScheduleCreateRequest body) {
    String audience =
        body.audience() == null
            ? "STANDARD"
            : requireOneOf("audience", body.audience(), PRICE_AUDIENCES);
    Instant effectiveAt = body.effectiveAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    Instant noticeAt = body.noticeAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    if (noticeAt.plus(NOTICE_LEAD).isAfter(effectiveAt)) {
      throw OpsQueryParams.invalid(
          "noticeAt", FieldErrorCode.OUT_OF_RANGE, "공지 시각은 시행 시각보다 30일 이상 앞서야 합니다.");
    }
    if (audience.equals("STANDARD") && repository.liveStandardAt(effectiveAt, null)) {
      throw OpsQueryParams.invalid(
          "effectiveAt", FieldErrorCode.NOT_ALLOWED, "같은 효력 시각의 예약·시행 표준 요금표가 이미 있습니다.");
    }
    advisoryLock.lockForTransaction("ops:price-schedule-version");
    String version = nextVersion(effectiveAt, audience);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    long id =
        repository.insertPrice(
            new NewPrice(
                version,
                body.soloAmount(),
                body.teamFirstExtra(),
                body.teamAfterFourExtra(),
                effectiveAt,
                noticeAt,
                audience,
                operatorId,
                now));
    auditLog.insert(operatorId, "PRICE_SCHEDULE_CREATED", "PRICE_SCHEDULE", id, version, now);
    PriceRow row = repository.price(id).orElseThrow();
    return new PriceScheduleCreateResponse(
        row.id(),
        row.version(),
        row.status(),
        toDisplayOffset(row.effectiveAt()),
        toDisplayOffset(row.noticeAt()),
        toDisplayOffset(row.createdAt()),
        row.audience());
  }

  /** 효력 시각의 KST 연월과 대상으로 `YYYY-MM-STD`·`YYYY-MM-START`를 만들고 이미 있으면 `-2`, `-3`을 붙인다. */
  String nextVersion(Instant effectiveAt, String audience) {
    ZonedDateTime display = TimeRules.toDisplay(effectiveAt);
    String base =
        String.format(
            "%04d-%02d-%s",
            display.getYear(),
            display.getMonthValue(),
            audience.equals("STANDARD") ? "STD" : "START");
    Set<String> taken = new HashSet<>(repository.versionsStartingWith(base));
    if (!taken.contains(base)) {
      return base;
    }
    int suffix = 2;
    while (taken.contains(base + "-" + suffix)) {
      suffix++;
    }
    return base + "-" + suffix;
  }

  @Transactional
  public PriceScheduleApproveResponse approvePrice(
      long operatorId, long scheduleId, HttpSession session) {
    PriceRow row = repository.lockPrice(scheduleId).orElseThrow(OpsTrialPriceService::notFound);
    stepUp.require(session);
    if (row.createdByOperatorId() == operatorId) {
      throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }
    if (!accounts.isActiveWithRole(operatorId, OperatorRole.BILLING)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (!row.status().equals("DRAFT") && !row.status().equals("REVIEW")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (row.audience().equals("STANDARD")) {
      advisoryLock.lockForTransaction("billing:price-schedule-standard");
      if (repository.liveStandardAt(row.effectiveAt(), row.id())) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String status = row.effectiveAt().isAfter(now) ? "SCHEDULED" : "ACTIVE";
    repository.approvePrice(scheduleId, status, operatorId);
    auditLog.insert(
        operatorId,
        "PRICE_SCHEDULE_APPROVED",
        "PRICE_SCHEDULE",
        scheduleId,
        row.version() + " " + status,
        now);
    return new PriceScheduleApproveResponse(scheduleId, row.version(), status, operatorId);
  }

  // 체험 이벤트

  @Transactional(readOnly = true)
  public TrialEventListResponse events(String status, String audience, PageQuery page) {
    EventFilter filter =
        new EventFilter(
            OpsQueryParams.oneOf("status", status, STATUSES),
            OpsQueryParams.oneOf("audience", audience, EVENT_AUDIENCES));
    List<TrialEventItem> items =
        repository.events(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsTrialPriceService::toItem)
            .toList();
    return new TrialEventListResponse(
        items, page.page(), page.size(), repository.countEvents(filter));
  }

  @Transactional
  public TrialEventCreateResponse createEvent(long operatorId, TrialEventCreateRequest body) {
    String audience =
        body.audience() == null
            ? "GENERAL"
            : requireOneOf("audience", body.audience(), EVENT_AUDIENCES);
    String mode =
        body.freeEndMode() == null
            ? "DURATION"
            : requireOneOf("freeEndMode", body.freeEndMode(), FREE_END_MODES);
    Instant startsAt = body.startsAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    Instant endsAt = body.endsAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    if (!endsAt.isAfter(startsAt)) {
      throw OpsQueryParams.invalid(
          "endsAt", FieldErrorCode.OUT_OF_RANGE, "종료 시각은 시작 시각보다 뒤여야 합니다.");
    }
    Instant fixedEnd =
        body.fixedFreeEndsAt() == null
            ? null
            : body.fixedFreeEndsAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    if (mode.equals("FIXED_END") && fixedEnd == null) {
      throw OpsQueryParams.invalid(
          "fixedFreeEndsAt", FieldErrorCode.REQUIRED, "FIXED_END이면 고정 무료 종료 시각이 필요합니다.");
    }
    if (mode.equals("DURATION") && fixedEnd != null) {
      throw OpsQueryParams.invalid(
          "fixedFreeEndsAt", FieldErrorCode.NOT_ALLOWED, "DURATION이면 고정 무료 종료 시각을 보내지 않습니다.");
    }
    if (fixedEnd != null && !fixedEnd.isAfter(startsAt)) {
      throw OpsQueryParams.invalid(
          "fixedFreeEndsAt", FieldErrorCode.OUT_OF_RANGE, "고정 무료 종료 시각은 이벤트 시작보다 뒤여야 합니다.");
    }
    if (body.maxOffices() != null && !audience.equals("START_CUSTOMER")) {
      throw OpsQueryParams.invalid(
          "maxOffices", FieldErrorCode.NOT_ALLOWED, "승인 상한은 시작 고객 이벤트에만 둡니다.");
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    long id =
        repository.insertEvent(
            new NewEvent(
                body.name().strip(),
                startsAt,
                endsAt,
                body.officeCreatedDuring() == null || body.officeCreatedDuring(),
                body.firstApplicationDuring() == null || body.firstApplicationDuring(),
                mode,
                fixedEnd,
                audience,
                body.maxOffices(),
                operatorId,
                now));
    auditLog.insert(operatorId, "TRIAL_EVENT_CREATED", "TRIAL_EVENT", id, audience, now);
    EventRow row = repository.event(id).orElseThrow();
    return new TrialEventCreateResponse(
        row.id(),
        row.name(),
        row.status(),
        toDisplayOffset(row.startsAt()),
        toDisplayOffset(row.endsAt()),
        row.freeEndMode(),
        toDisplayOffset(row.fixedFreeEndsAt()),
        toDisplayOffset(row.createdAt()),
        row.audience(),
        row.maxOffices());
  }

  @Transactional(readOnly = true)
  public TrialEventPreviewResponse previewEvent(long eventId) {
    EventRow event = repository.event(eventId).orElseThrow(OpsTrialPriceService::notFound);
    List<OfficeRef> targets =
        event.audience().equals("START_CUSTOMER")
            ? repository.startCustomerTargets(eventId)
            : repository.generalTargets(event);
    Set<Long> benefited = new HashSet<>(repository.officesWithOtherTrial(eventId));
    List<DuplicateOffice> duplicates =
        targets.stream()
            .filter(office -> benefited.contains(office.id()))
            .map(office -> new DuplicateOffice(office.id(), office.name()))
            .toList();
    return new TrialEventPreviewResponse(eventId, targets.size(), duplicates.size(), duplicates);
  }

  @Transactional
  public TrialEventApproveResponse approveEvent(long operatorId, long eventId) {
    EventRow row = repository.lockEvent(eventId).orElseThrow(OpsTrialPriceService::notFound);
    if (row.createdByOperatorId() == operatorId) {
      throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }
    if (!accounts.isActiveWithRole(operatorId, OperatorRole.OPS)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (!row.status().equals("REVIEW")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (row.audience().equals("GENERAL")) {
      advisoryLock.lockForTransaction("billing:trial-event-general-window");
      if (repository.generalWindowOverlaps(eventId, row.startsAt(), row.endsAt())) {
        throw new BusinessException(ErrorCode.TRIAL_EVENT_WINDOW_OVERLAP);
      }
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String status = row.startsAt().isAfter(now) ? "SCHEDULED" : "ACTIVE";
    repository.approveEvent(eventId, status, operatorId);
    auditLog.insert(
        operatorId, "TRIAL_EVENT_APPROVED", "TRIAL_EVENT", eventId, row.name() + " " + status, now);
    return new TrialEventApproveResponse(eventId, status, operatorId);
  }

  @Transactional
  public TrialEventStopResponse stopEvent(long operatorId, long eventId) {
    EventRow row = repository.lockEvent(eventId).orElseThrow(OpsTrialPriceService::notFound);
    if (!row.status().equals("SCHEDULED") && !row.status().equals("ACTIVE")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant endsAt = row.endsAt().isAfter(now) && now.isAfter(row.startsAt()) ? now : row.endsAt();
    repository.stopEvent(eventId, endsAt);
    auditLog.insert(operatorId, "TRIAL_EVENT_STOPPED", "TRIAL_EVENT", eventId, row.name(), now);
    return new TrialEventStopResponse(eventId, "ENDED", toDisplayOffset(endsAt));
  }

  // 영업일 달력

  @Transactional(readOnly = true)
  public BusinessCalendarResponse calendar(String from, String to, PageQuery page) {
    LocalDate fromDate = date("from", from);
    LocalDate toDate = date("to", to);
    if (fromDate != null && toDate != null) {
      if (toDate.isBefore(fromDate)) {
        throw OpsQueryParams.invalid("to", FieldErrorCode.OUT_OF_RANGE, "to는 from 이후 날짜여야 합니다.");
      }
      if (ChronoUnit.DAYS.between(fromDate, toDate) > CALENDAR_RANGE_DAYS) {
        throw OpsQueryParams.invalid("to", FieldErrorCode.OUT_OF_RANGE, "조회 기간은 366일 이하여야 합니다.");
      }
    }
    List<BusinessCalendarItem> items =
        repository
            .calendar(fromDate, toDate, page.size(), (long) page.page() * page.size())
            .stream()
            .map(
                row ->
                    new BusinessCalendarItem(row.calendarDate(), row.isBusinessDay(), row.reason()))
            .toList();
    return new BusinessCalendarResponse(
        items, page.page(), page.size(), repository.countCalendar(fromDate, toDate));
  }

  @Transactional
  public BusinessCalendarItem updateCalendar(
      long operatorId, String rawDate, BusinessCalendarUpdateRequest body) {
    LocalDate date = date("date", rawDate);
    if (date == null) {
      throw OpsQueryParams.invalid("date", FieldErrorCode.REQUIRED, "날짜가 필요합니다.");
    }
    String reason = body.reason() == null || body.reason().isBlank() ? null : body.reason().strip();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    repository.upsertCalendar(date, body.isBusinessDay(), reason, operatorId, now);
    auditLog.insert(
        operatorId,
        "BUSINESS_CALENDAR_UPDATED",
        "BUSINESS_CALENDAR",
        null,
        date + (body.isBusinessDay() ? " 영업일" : " 휴무일"),
        now);
    return new BusinessCalendarItem(date, body.isBusinessDay(), reason);
  }

  private static LocalDate date(String field, String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.strip());
    } catch (DateTimeParseException e) {
      throw OpsQueryParams.invalid(
          field, FieldErrorCode.INVALID_FORMAT, field + "는 YYYY-MM-DD 날짜여야 합니다.");
    }
  }

  private static String requireOneOf(String field, String raw, Set<String> allowed) {
    String value = OpsQueryParams.oneOf(field, raw, allowed);
    if (value == null) {
      throw OpsQueryParams.invalid(
          field, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 " + field + " 값입니다.");
    }
    return value;
  }

  private static PriceScheduleItem toItem(PriceRow row) {
    return new PriceScheduleItem(
        row.id(),
        row.version(),
        row.soloAmount(),
        row.teamFirstExtra(),
        row.teamAfterFourExtra(),
        toDisplayOffset(row.effectiveAt()),
        toDisplayOffset(row.noticeAt()),
        row.status(),
        row.approvedByOperatorId(),
        toDisplayOffset(row.createdAt()),
        row.audience(),
        row.createdByOperatorId());
  }

  private static TrialEventItem toItem(EventRow row) {
    return new TrialEventItem(
        row.id(),
        row.name(),
        toDisplayOffset(row.startsAt()),
        toDisplayOffset(row.endsAt()),
        row.officeCreatedDuring(),
        row.firstApplicationDuring(),
        row.freeEndMode(),
        toDisplayOffset(row.fixedFreeEndsAt()),
        row.status(),
        row.approvedByOperatorId(),
        toDisplayOffset(row.createdAt()),
        row.audience(),
        row.maxOffices(),
        row.createdByOperatorId());
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
  }
}
