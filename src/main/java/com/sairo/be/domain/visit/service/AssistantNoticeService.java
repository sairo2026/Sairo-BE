package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.customer.service.CustomerInput;
import com.sairo.be.domain.property.service.ManagingMemberDirectory;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.AssistantNoticeListResponse;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.AssistantNoticeResponse;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.AssistantNoticeSupervisionListResponse;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.NoticeItem;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.RequiredRecipient;
import com.sairo.be.domain.visit.dto.response.AssistantNoticeResponses.SupervisionItem;
import com.sairo.be.domain.visit.entity.VisitCoordination;
import com.sairo.be.domain.visit.repository.AssistantNoticeRepository;
import com.sairo.be.domain.visit.repository.AssistantNoticeRepository.Filter;
import com.sairo.be.domain.visit.repository.AssistantNoticeRepository.NoticeInsert;
import com.sairo.be.domain.visit.repository.AssistantNoticeRepository.NoticeRow;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository;
import com.sairo.be.domain.visit.repository.ScheduleResultRepository.ItemHead;
import com.sairo.be.domain.visit.repository.VisitCoordinationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 중개보조원 배정 방문의 법정 신분 고지 기록과 감독 확인을 처리한다. */
@Profile("!migrate")
@Service
public class AssistantNoticeService {

  static final Duration LATE_ENTRY = Duration.ofMinutes(10);
  private static final Set<String> KEYS =
      Set.of(
          "recipientType",
          "recipientName",
          "noticeMethod",
          "notifiedAt",
          "missingExceptionReason",
          "followupAction");
  private static final Set<String> RECIPIENTS = Set.of("CUSTOMER", "APPROVER", "OTHER");
  private static final Set<String> METHODS = Set.of("KAKAO", "SMS", "PHONE", "IN_PERSON", "EMAIL");
  private static final Set<String> CONFIRM_KEYS = Set.of("supervisorAdditionalNotice");
  private static final int TEXT_MAX = 500;
  private static final int NAME_MAX = 100;

  private final ScheduleResultRepository resultRepository;
  private final AssistantNoticeRepository noticeRepository;
  private final VisitCoordinationRepository coordinationRepository;
  private final ManagingMemberDirectory memberDirectory;
  private final CoordinationAccess access;
  private final Clock clock;

  public AssistantNoticeService(
      ScheduleResultRepository resultRepository,
      AssistantNoticeRepository noticeRepository,
      VisitCoordinationRepository coordinationRepository,
      ManagingMemberDirectory memberDirectory,
      CoordinationAccess access,
      Clock clock) {
    this.resultRepository = resultRepository;
    this.noticeRepository = noticeRepository;
    this.coordinationRepository = coordinationRepository;
    this.memberDirectory = memberDirectory;
    this.access = access;
    this.clock = clock;
  }

  @Transactional
  public AssistantNoticeResponse record(StaffPrincipal principal, Long itemId, JsonNode body) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    CustomerInput.requireObject(body, KEYS);
    String recipientType = choice(body, "recipientType", RECIPIENTS);
    String recipientName = CustomerInput.requiredText(body, "recipientName", NAME_MAX, false);
    String method = choice(body, "noticeMethod", METHODS);
    OffsetDateTime notified = CustomerInput.optionalDateTime(body, "notifiedAt");
    String missing = CustomerInput.optionalText(body, "missingExceptionReason", TEXT_MAX, false);
    String followup = CustomerInput.optionalText(body, "followupAction", TEXT_MAX, false);
    if ((notified == null) == (missing == null)) {
      throw CustomerInput.invalid(
          "notifiedAt", FieldErrorCode.INVALID_VALUE, "고지 시각과 미고지 사유 중 하나만 입력합니다.");
    }
    if (notified != null && notified.toInstant().isAfter(now)) {
      throw CustomerInput.invalid("notifiedAt", FieldErrorCode.OUT_OF_RANGE, "미래 시각은 입력할 수 없습니다.");
    }
    ItemHead item = ownItem(principal, itemId);
    VisitCoordination coordination =
        coordinationRepository
            .findById(item.coordinationId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Long deputyId = coordination.getManagerMembershipId();
    boolean deputyManaged =
        memberDirectory.find(deputyId).map(member -> "DEPUTY".equals(member.role())).orElse(false);
    if (!deputyManaged) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (!deputyId.equals(principal.membershipId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    Instant notifiedAt =
        notified == null ? null : notified.toInstant().truncatedTo(ChronoUnit.MICROS);
    boolean afterTheFact =
        (item.startAt() != null && now.isAfter(item.startAt()))
            || (notifiedAt != null && now.isAfter(notifiedAt.plus(LATE_ENTRY)));
    if ((afterTheFact || notifiedAt == null) && followup == null) {
      throw CustomerInput.invalid(
          "followupAction", FieldErrorCode.REQUIRED, "사후 입력이나 미고지에는 후속 조치가 필요합니다.");
    }
    Long noticeId =
        noticeRepository.insert(
            new NoticeInsert(
                item.id(),
                deputyId,
                recipientType,
                recipientName,
                method,
                notifiedAt,
                afterTheFact,
                missing,
                followup,
                now));
    return response(noticeRepository.find(noticeId).orElseThrow());
  }

  @Transactional
  public AssistantNoticeResponse confirm(StaffPrincipal principal, Long noticeId, JsonNode body) {
    String additionalNotice = null;
    if (body != null && !body.isNull() && !body.isMissingNode()) {
      CustomerInput.requireObject(body, CONFIRM_KEYS);
      additionalNotice =
          CustomerInput.optionalText(body, "supervisorAdditionalNotice", TEXT_MAX, false);
    }
    NoticeRow notice =
        noticeRepository
            .find(noticeId)
            .filter(found -> found.officeId().equals(principal.officeId()))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (notice.confirmedAt() != null
        || noticeRepository.confirm(
                noticeId,
                principal.membershipId(),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                additionalNotice)
            == 0) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    return response(noticeRepository.find(noticeId).orElseThrow());
  }

  @Transactional(readOnly = true)
  public AssistantNoticeListResponse list(StaffPrincipal principal, Long itemId) {
    ItemHead item = ownItem(principal, itemId);
    access.view(principal, item.coordinationId());
    List<NoticeRow> rows = noticeRepository.listByItem(itemId);
    boolean customer = rows.stream().anyMatch(row -> "CUSTOMER".equals(row.recipientType()));
    boolean approver = rows.stream().anyMatch(row -> "APPROVER".equals(row.recipientType()));
    return new AssistantNoticeListResponse(
        rows.stream()
            .map(
                row ->
                    new NoticeItem(
                        row.id(),
                        row.recipientType(),
                        row.recipientName(),
                        row.noticeMethod(),
                        TimeRules.toDisplayOffset(row.notifiedAt()),
                        row.missingExceptionReason(),
                        row.followupAction(),
                        row.recordedAfterTheFact(),
                        TimeRules.toDisplayOffset(row.confirmedAt())))
            .toList(),
        List.of(
            new RequiredRecipient("CUSTOMER", null, customer),
            new RequiredRecipient("APPROVER", item.candidatePropertyId(), approver)));
  }

  @Transactional(readOnly = true)
  public AssistantNoticeSupervisionListResponse supervision(
      StaffPrincipal principal,
      String from,
      String to,
      Long assistantMembershipId,
      Boolean confirmed,
      Boolean recordedAfterTheFact,
      PageQuery page) {
    LocalDate fromDate = date("from", from);
    LocalDate toDate = date("to", to);
    if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
      throw CustomerInput.invalid("to", FieldErrorCode.OUT_OF_RANGE, "시작일 이후여야 합니다.");
    }
    Filter filter =
        new Filter(
            principal.officeId(),
            fromDate == null ? null : TimeRules.startOfDisplayDay(fromDate),
            toDate == null ? null : TimeRules.startOfDisplayDay(toDate.plusDays(1)),
            assistantMembershipId,
            confirmed,
            recordedAfterTheFact);
    List<SupervisionItem> items =
        noticeRepository.page(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new SupervisionItem(
                        row.id(),
                        row.scheduleItemId(),
                        row.deputyMembershipId(),
                        row.recipientType(),
                        TimeRules.toDisplayOffset(row.notifiedAt()),
                        row.recordedAfterTheFact(),
                        TimeRules.toDisplayOffset(row.confirmedAt())))
            .toList();
    return new AssistantNoticeSupervisionListResponse(
        items, page.page(), page.size(), noticeRepository.count(filter));
  }

  private ItemHead ownItem(StaffPrincipal principal, Long itemId) {
    return resultRepository
        .findItem(itemId)
        .filter(found -> found.officeId().equals(principal.officeId()))
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private static AssistantNoticeResponse response(NoticeRow row) {
    return new AssistantNoticeResponse(
        row.id(),
        row.scheduleItemId(),
        row.recipientType(),
        row.noticeMethod(),
        TimeRules.toDisplayOffset(row.notifiedAt()),
        row.recordedAfterTheFact(),
        row.recipientName(),
        row.missingExceptionReason(),
        row.followupAction(),
        TimeRules.toDisplayOffset(row.createdAt()),
        row.deputyMembershipId(),
        row.confirmedByMembershipId(),
        TimeRules.toDisplayOffset(row.confirmedAt()),
        row.supervisorAdditionalNotice(),
        TimeRules.toDisplayOffset(row.supervisorAdditionalNoticedAt()));
  }

  private static String choice(JsonNode body, String key, Set<String> allowed) {
    if (CustomerInput.isNull(body, key)) {
      throw CustomerInput.invalid(key, FieldErrorCode.REQUIRED, "필수 입력입니다.");
    }
    JsonNode node = body.get(key);
    if (!node.isString() || !allowed.contains(node.asString())) {
      throw CustomerInput.invalid(key, FieldErrorCode.NOT_ALLOWED, "허용되지 않는 값입니다.");
    }
    return node.asString();
  }

  private static LocalDate date(String field, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw CustomerInput.invalid(field, FieldErrorCode.INVALID_FORMAT, "날짜 형식이 아닙니다.");
    }
  }
}
