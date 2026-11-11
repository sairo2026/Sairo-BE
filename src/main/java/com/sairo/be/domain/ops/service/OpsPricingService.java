package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.billing.service.PriceChangeNoticeIssuer;
import com.sairo.be.domain.billing.service.PriceChangeNoticeIssuer.IssueResult;
import com.sairo.be.domain.billing.service.SubscriptionAllocationService;
import com.sairo.be.domain.ops.dto.request.OpsPricingRequests.PriceChangeNoticeCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsPricingRequests.StartCustomerAgreementCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsPricingRequests.StartCustomerAgreementRevokeRequest;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementApproveResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementItem;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementListResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.AgreementRevokeResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.ApprovalCohort;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.ApprovalSubscription;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.Cohort;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.NoticeCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.NoticeItem;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.NoticeListResponse;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.NoticeSummary;
import com.sairo.be.domain.ops.dto.response.OpsPricingResponses.SkippedOffice;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsPricingRepository;
import com.sairo.be.domain.ops.repository.OpsPricingRepository.AgreementFilter;
import com.sairo.be.domain.ops.repository.OpsPricingRepository.AgreementRow;
import com.sairo.be.domain.ops.repository.OpsPricingRepository.EventRow;
import com.sairo.be.domain.ops.repository.OpsPricingRepository.NewAgreement;
import com.sairo.be.domain.ops.repository.OpsPricingRepository.NoticeFilter;
import com.sairo.be.domain.ops.repository.OpsPricingRepository.PriceRow;
import com.sairo.be.domain.ops.repository.OpsPricingRepository.SubscriptionState;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시작 고객 합의 등록·승인·취소와 가격 변경 안내 생성·현황이다(OPS-21·22). 승인은 등록자와 다른 결제담당만 하고(자기 승인 403 → DB 역할 재확인 순),
 * 이벤트 단위 잠금 아래 가입 창과 상한을 검사한 뒤 구독을 시작 고객 이벤트로 승격한다.
 */
@Profile("!migrate")
@Service
public class OpsPricingService {

  static final Set<String> AGREEMENT_STATUSES = Set.of("REGISTERED", "APPROVED", "REVOKED");
  static final Set<String> DECISIONS =
      Set.of("PENDING", "AGREED", "DECLINED", "NOT_REQUIRED", "ENDED_NO_CONSENT");
  static final Set<String> LIVE_STATUSES = Set.of("SCHEDULED", "ACTIVE");
  static final int DEFAULT_GUARANTEE_MONTHS = 12;
  static final int COHORT_TARGET = 10;

  private final OpsPricingRepository pricing;
  private final PriceChangeNoticeIssuer noticeIssuer;
  private final SubscriptionAllocationService allocation;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public OpsPricingService(
      OpsPricingRepository pricing,
      PriceChangeNoticeIssuer noticeIssuer,
      SubscriptionAllocationService allocation,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.pricing = pricing;
    this.noticeIssuer = noticeIssuer;
    this.allocation = allocation;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  // 시작 고객 합의

  @Transactional(readOnly = true)
  public AgreementListResponse agreements(
      String status, Long trialEventId, Long officeId, PageQuery page) {
    AgreementFilter filter =
        new AgreementFilter(
            OpsQueryParams.oneOf("status", status, AGREEMENT_STATUSES),
            OpsQueryParams.positiveId("trialEventId", trialEventId),
            OpsQueryParams.positiveId("officeId", officeId));
    List<AgreementItem> items =
        pricing.agreements(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsPricingService::toItem)
            .toList();
    List<Cohort> cohorts =
        pricing.cohorts(filter.trialEventId()).stream()
            .map(
                row ->
                    new Cohort(
                        row.trialEventId(),
                        row.eventName(),
                        row.approvedCount(),
                        row.maxOffices(),
                        COHORT_TARGET))
            .toList();
    return new AgreementListResponse(
        items, cohorts, page.page(), page.size(), pricing.countAgreements(filter));
  }

  @Transactional
  public AgreementCreateResponse createAgreement(
      long operatorId, StartCustomerAgreementCreateRequest body) {
    if (!pricing.officeExists(body.officeId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    EventRow event =
        pricing
            .event(body.trialEventId())
            .filter(row -> row.audience().equals("START_CUSTOMER"))
            .filter(row -> LIVE_STATUSES.contains(row.status()))
            .filter(row -> row.fixedFreeEndsAt() != null)
            .orElseThrow(
                () ->
                    OpsQueryParams.invalid(
                        "trialEventId",
                        FieldErrorCode.NOT_ALLOWED,
                        "예약·진행 중이고 고정 무료 종료 시각이 있는 시작 고객 이벤트여야 합니다."));
    PriceRow price =
        pricing
            .price(body.priceScheduleId())
            .filter(row -> row.audience().equals("START_CUSTOMER"))
            .filter(row -> LIVE_STATUSES.contains(row.status()))
            .orElseThrow(
                () ->
                    OpsQueryParams.invalid(
                        "priceScheduleId",
                        FieldErrorCode.NOT_ALLOWED,
                        "예약·시행 중인 시작 고객 전용 가격 버전이어야 합니다."));
    advisoryLock.lockForTransaction("billing:start-customer-office:" + body.officeId());
    if (pricing.hasLiveAgreement(body.officeId())) {
      throw new BusinessException(ErrorCode.START_CUSTOMER_AGREEMENT_EXISTS);
    }
    LocalDate windowStart = TimeRules.displayDate(event.startsAt());
    LocalDate windowEnd = TimeRules.displayDate(event.endsAt());
    if (body.contractedOn().isBefore(windowStart) || !body.contractedOn().isBefore(windowEnd)) {
      throw new BusinessException(ErrorCode.START_CUSTOMER_WINDOW_CLOSED);
    }
    int months = body.guaranteeMonths() == null ? DEFAULT_GUARANTEE_MONTHS : body.guaranteeMonths();
    Instant priceStartsAt = event.fixedFreeEndsAt();
    Instant priceEndsAt = TimeRules.toDisplay(priceStartsAt).plusMonths(months).toInstant();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    long agreementId =
        pricing.insertAgreement(
            new NewAgreement(
                body.officeId(),
                event.id(),
                price.id(),
                priceStartsAt,
                priceEndsAt,
                body.contractedOn(),
                body.contractReference().strip(),
                Boolean.TRUE.equals(body.casePublicationConsent()),
                body.agreementTextVersion().strip(),
                operatorId,
                now));
    auditLog.insert(
        operatorId,
        "START_CUSTOMER_AGREEMENT_REGISTERED",
        "START_CUSTOMER_AGREEMENT",
        agreementId,
        "사무소 " + body.officeId() + " 이벤트 " + event.id(),
        now);
    AgreementRow row = pricing.agreement(agreementId).orElseThrow();
    return new AgreementCreateResponse(
        row.id(),
        row.officeId(),
        row.status(),
        row.trialEventId(),
        row.priceScheduleId(),
        row.priceVersion(),
        toDisplayOffset(row.priceStartsAt()),
        toDisplayOffset(row.priceEndsAt()),
        row.contractedOn(),
        toDisplayOffset(row.createdAt()),
        row.version());
  }

  @Transactional
  public AgreementApproveResponse approve(long operatorId, long agreementId, String ifMatch) {
    long expected = IfMatchVersion.parse(ifMatch);
    AgreementRow row = pricing.lockAgreement(agreementId).orElseThrow(OpsPricingService::notFound);
    if (row.createdByOperatorId() == operatorId) {
      throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
    }
    if (!accounts.isActiveWithRole(operatorId, OperatorRole.BILLING)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (row.version() != expected) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    if (!row.status().equals("REGISTERED")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    advisoryLock.lockForTransaction("billing:start-customer-event:" + row.trialEventId());
    EventRow event = pricing.event(row.trialEventId()).orElseThrow();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (now.isBefore(event.startsAt()) || !now.isBefore(event.endsAt())) {
      throw new BusinessException(ErrorCode.START_CUSTOMER_WINDOW_CLOSED);
    }
    long approved = pricing.countApproved(event.id());
    if (event.maxOffices() != null && approved >= event.maxOffices()) {
      throw new BusinessException(ErrorCode.START_CUSTOMER_QUOTA_EXCEEDED);
    }
    pricing.approve(agreementId, operatorId, now);
    Optional<SubscriptionState> before = pricing.subscription(row.officeId());
    allocation.promoteStartCustomer(row.officeId());
    ApprovalSubscription subscription =
        pricing
            .subscription(row.officeId())
            .map(
                after ->
                    new ApprovalSubscription(
                        after.id(),
                        after.status(),
                        after.status().equals("TRIAL")
                            ? toDisplayOffset(after.trialEndsAt())
                            : null,
                        toDisplayOffset(after.firstBillingScheduledAt()),
                        before.isPresent()
                            && (!Objects.equals(before.get().trialEndsAt(), after.trialEndsAt())
                                || !before.get().status().equals(after.status()))))
            .orElse(null);
    auditLog.insert(
        operatorId,
        "START_CUSTOMER_AGREEMENT_APPROVED",
        "START_CUSTOMER_AGREEMENT",
        agreementId,
        "이벤트 " + event.id() + " 승인 " + (approved + 1) + "곳",
        now);
    return new AgreementApproveResponse(
        agreementId,
        "APPROVED",
        toDisplayOffset(now),
        operatorId,
        new ApprovalCohort(event.id(), approved + 1, event.maxOffices()),
        subscription,
        row.version() + 1);
  }

  @Transactional
  public AgreementRevokeResponse revoke(
      long operatorId, long agreementId, String ifMatch, StartCustomerAgreementRevokeRequest body) {
    long expected = IfMatchVersion.parse(ifMatch);
    AgreementRow row = pricing.lockAgreement(agreementId).orElseThrow(OpsPricingService::notFound);
    if (row.version() != expected) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    if (row.status().equals("REVOKED")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String reason = body.reason().strip();
    pricing.revoke(agreementId, operatorId, reason, now);
    Long noticeId =
        row.status().equals("APPROVED")
            ? noticeIssuer
                .issueForRevokedAgreement(
                    row.officeId(), row.priceScheduleId(), row.priceEndsAt(), now)
                .orElse(null)
            : null;
    auditLog.insert(
        operatorId,
        "START_CUSTOMER_AGREEMENT_REVOKED",
        "START_CUSTOMER_AGREEMENT",
        agreementId,
        reason,
        now);
    return new AgreementRevokeResponse(
        agreementId,
        "REVOKED",
        toDisplayOffset(now),
        operatorId,
        true,
        noticeId,
        row.version() + 1);
  }

  // 가격 변경 안내

  @Transactional(readOnly = true)
  public NoticeListResponse notices(
      Long priceScheduleId, String decision, Long officeId, PageQuery page) {
    NoticeFilter filter =
        new NoticeFilter(
            OpsQueryParams.positiveId("priceScheduleId", priceScheduleId),
            OpsQueryParams.oneOf("decision", decision, DECISIONS),
            OpsQueryParams.positiveId("officeId", officeId));
    List<NoticeItem> items =
        pricing.notices(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new NoticeItem(
                        row.id(),
                        row.officeId(),
                        row.officeName(),
                        row.subscriptionId(),
                        row.changeKind(),
                        row.fromVersion(),
                        row.toVersion(),
                        toDisplayOffset(row.notifiedAt()),
                        toDisplayOffset(row.effectiveAt()),
                        toDisplayOffset(row.responseDueAt()),
                        row.decision(),
                        toDisplayOffset(row.decidedAt())))
            .toList();
    Map<String, Long> counts = pricing.noticeSummary(filter);
    NoticeSummary summary =
        new NoticeSummary(
            counts.getOrDefault("PENDING", 0L),
            counts.getOrDefault("AGREED", 0L),
            counts.getOrDefault("DECLINED", 0L),
            counts.getOrDefault("NOT_REQUIRED", 0L),
            counts.getOrDefault("ENDED_NO_CONSENT", 0L));
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    return new NoticeListResponse(items, summary, page.page(), page.size(), total);
  }

  @Transactional
  public NoticeCreateResponse createNotices(
      long operatorId, long scheduleId, PriceChangeNoticeCreateRequest body) {
    List<Long> officeIds = body == null ? null : body.officeIds();
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    IssueResult result = noticeIssuer.issueForSchedule(scheduleId, officeIds, now);
    List<SkippedOffice> skipped =
        result.skipped().stream()
            .map(item -> new SkippedOffice(item.officeId(), item.reason()))
            .toList();
    auditLog.insert(
        operatorId,
        "PRICE_CHANGE_NOTICES_CREATED",
        "PRICE_SCHEDULE",
        scheduleId,
        "생성 " + result.createdNoticeIds().size() + " 건너뜀 " + skipped.size(),
        now);
    return new NoticeCreateResponse(
        scheduleId, result.createdNoticeIds().size(), skipped.size(), skipped);
  }

  private static AgreementItem toItem(AgreementRow row) {
    return new AgreementItem(
        row.id(),
        row.officeId(),
        row.officeName(),
        row.status(),
        row.trialEventId(),
        row.priceScheduleId(),
        row.priceVersion(),
        toDisplayOffset(row.priceStartsAt()),
        toDisplayOffset(row.priceEndsAt()),
        row.contractedOn(),
        row.contractReference(),
        row.createdByOperatorId(),
        row.approvedByOperatorId(),
        toDisplayOffset(row.approvedAt()),
        toDisplayOffset(row.ownerAcknowledgedAt()),
        row.version());
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
  }
}
