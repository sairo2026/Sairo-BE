package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.request.OpsContentRequests.AnnouncementPublishRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.AnnouncementRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.CompanyServiceInfoRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.InquiryTestRequest;
import com.sairo.be.domain.ops.dto.request.OpsContentRequests.PolicyDocumentCreateRequest;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementEndResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementItem;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementListResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementPreviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementRevisionResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementStateResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.AnnouncementSubmitReviewResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.CompanyServiceInfoCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.CompanyServiceInfoItem;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.CompanyServiceInfoResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.InquiryTestResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.OpsPolicyDocumentListResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.PolicyDocumentCreateResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.PolicyDocumentItem;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.PolicyDocumentPublishResponse;
import com.sairo.be.domain.ops.dto.response.OpsContentResponses.PolicyDocumentSubmitReviewResponse;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsContentRepository;
import com.sairo.be.domain.ops.repository.OpsContentRepository.AnnouncementRow;
import com.sairo.be.domain.ops.repository.OpsContentRepository.InfoRow;
import com.sairo.be.domain.ops.repository.OpsContentRepository.NewAnnouncement;
import com.sairo.be.domain.ops.repository.OpsContentRepository.NewPolicy;
import com.sairo.be.domain.ops.repository.OpsContentRepository.PolicyFilter;
import com.sairo.be.domain.ops.repository.OpsContentRepository.PolicyRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.PageQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지(초안 → 검토 → 예약·게시 → 종료, 게시본 수정은 새 버전), 정책 문서(초안 → 검토 → 게시, 게시본은 덮어쓰지 않음), 회사 제공 정보(버전 등록, 문의 수신
 * 시험 성공 때 게시)다(OPS-08). 검토 단계로 올린 운영자를 검토자로, 게시한 운영자를 게시자로 기록하고 모든 처리에 감사 로그를 남긴다.
 */
@Profile("!migrate")
@Service
public class OpsContentService {

  static final Set<String> STATUSES = Set.of("DRAFT", "REVIEW", "SCHEDULED", "PUBLISHED", "ENDED");
  static final Set<String> AUDIENCE_TYPES = Set.of("ALL", "SUBSCRIPTION_STATUS", "OFFICE");
  static final Set<String> AUDIENCE_STATUSES =
      Set.of(
          "PRE_PAYMENT",
          "TRIAL",
          "ACTIVE",
          "PAST_DUE_GRACE",
          "PAST_DUE_RESTRICTED",
          "SUSPENDED",
          "ENDED");
  static final Set<String> IMPORTANCES = Set.of("NORMAL", "IMPORTANT", "CRITICAL");
  static final Set<String> DOC_TYPES =
      Set.of(
          "TERMS",
          "PRIVACY",
          "PROCESSING_OUTSOURCING",
          "ARCHIVE",
          "REFUND",
          "SUBPROCESSORS",
          "PRICING");
  static final Set<String> TEST_RESULTS = Set.of("SUCCESS", "FAILED");
  static final Duration PRICING_LEAD = Duration.ofDays(30);
  static final String MISSING_PUBLISHED = "PUBLISHED_VERSION";
  static final String MISSING_TEST = "INQUIRY_DELIVERY_TEST";
  static final String MISSING_REVIEW = "REVIEW";
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private final OpsContentRepository repository;
  private final OpsAuditLogRepository auditLog;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public OpsContentService(
      OpsContentRepository repository,
      OpsAuditLogRepository auditLog,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.repository = repository;
    this.auditLog = auditLog;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  // 공지

  @Transactional(readOnly = true)
  public AnnouncementListResponse announcements(String status, PageQuery page) {
    String filter = OpsQueryParams.oneOf("status", status, STATUSES);
    List<AnnouncementItem> items =
        repository.announcements(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsContentService::toItem)
            .toList();
    return new AnnouncementListResponse(
        items, page.page(), page.size(), repository.countAnnouncements(filter));
  }

  @Transactional
  public AnnouncementStateResponse createAnnouncement(long operatorId, AnnouncementRequest body) {
    Instant now = now();
    long id = repository.insertAnnouncement(newAnnouncement(body, 1, null, operatorId, now));
    auditLog.insert(operatorId, "ANNOUNCEMENT_CREATED", "ANNOUNCEMENT", id, "버전 1", now);
    return new AnnouncementStateResponse(id, 1, "DRAFT", null, null);
  }

  @Transactional
  public AnnouncementSubmitReviewResponse submitAnnouncementReview(long operatorId, long id) {
    AnnouncementRow row = lockedAnnouncement(id);
    requireStatus(row.status(), "DRAFT");
    repository.submitAnnouncementReview(id, operatorId);
    auditLog.insert(
        operatorId, "ANNOUNCEMENT_REVIEW_SUBMITTED", "ANNOUNCEMENT", id, row.title(), now());
    return new AnnouncementSubmitReviewResponse(id, row.versionNo(), "REVIEW", operatorId);
  }

  @Transactional(readOnly = true)
  public AnnouncementPreviewResponse previewAnnouncement(long id) {
    AnnouncementRow row = repository.announcement(id).orElseThrow(OpsContentService::notFound);
    long targets = repository.countTargets(row, false);
    long excluded = row.marketing() ? repository.countTargets(row, true) : 0;
    return new AnnouncementPreviewResponse(
        id, targets, excluded, row.title() + "\n" + row.content());
  }

  @Transactional
  public AnnouncementStateResponse publishAnnouncement(
      long operatorId, long id, AnnouncementPublishRequest body) {
    Instant now = now();
    Instant scheduledAt =
        body == null || body.scheduledAt() == null
            ? null
            : body.scheduledAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    if (scheduledAt != null && !scheduledAt.isAfter(now)) {
      throw OpsQueryParams.invalid(
          "scheduledAt", FieldErrorCode.OUT_OF_RANGE, "예약 시각은 현재보다 뒤여야 합니다.");
    }
    AnnouncementRow row = lockedAnnouncement(id);
    requireStatus(row.status(), "REVIEW");
    if (scheduledAt == null) {
      repository.publishAnnouncement(id, now);
      auditLog.insert(operatorId, "ANNOUNCEMENT_PUBLISHED", "ANNOUNCEMENT", id, row.title(), now);
      return new AnnouncementStateResponse(
          id, row.versionNo(), "PUBLISHED", null, toDisplayOffset(now));
    }
    repository.scheduleAnnouncement(id, scheduledAt);
    auditLog.insert(
        operatorId,
        "ANNOUNCEMENT_SCHEDULED",
        "ANNOUNCEMENT",
        id,
        row.title() + " " + scheduledAt,
        now);
    return new AnnouncementStateResponse(
        id, row.versionNo(), "SCHEDULED", toDisplayOffset(scheduledAt), null);
  }

  @Transactional
  public AnnouncementEndResponse endAnnouncement(long operatorId, long id) {
    AnnouncementRow row = lockedAnnouncement(id);
    if (!row.status().equals("PUBLISHED") && !row.status().equals("SCHEDULED")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = now();
    repository.endAnnouncement(id, now);
    auditLog.insert(
        operatorId,
        "ANNOUNCEMENT_ENDED",
        "ANNOUNCEMENT",
        id,
        row.status().equals("SCHEDULED") ? "예약 취소" : "게시 종료",
        now);
    return new AnnouncementEndResponse(id, row.versionNo(), "ENDED", toDisplayOffset(now));
  }

  @Transactional
  public AnnouncementRevisionResponse reviseAnnouncement(
      long operatorId, long id, AnnouncementRequest body) {
    AnnouncementRow row = lockedAnnouncement(id);
    requireStatus(row.status(), "PUBLISHED");
    Instant now = now();
    int versionNo = row.versionNo() + 1;
    long newId =
        repository.insertAnnouncement(newAnnouncement(body, versionNo, id, operatorId, now));
    auditLog.insert(operatorId, "ANNOUNCEMENT_REVISED", "ANNOUNCEMENT", newId, "이전 게시본 " + id, now);
    return new AnnouncementRevisionResponse(newId, versionNo, id, "DRAFT");
  }

  /** 예약 시각이 지난 예약 공지를 게시한다. 배치가 부른다. */
  @Transactional
  public int publishDueAnnouncements(Instant now) {
    return repository.publishDueAnnouncements(now);
  }

  private NewAnnouncement newAnnouncement(
      AnnouncementRequest body, int versionNo, Long previousId, long operatorId, Instant now) {
    String audienceType = required("audienceType", body.audienceType(), AUDIENCE_TYPES);
    String importance = required("importance", body.importance(), IMPORTANCES);
    String audienceStatus = null;
    Long audienceOfficeId = null;
    if (audienceType.equals("SUBSCRIPTION_STATUS")) {
      if (body.audienceStatus() == null) {
        throw OpsQueryParams.invalid(
            "audienceStatus", FieldErrorCode.REQUIRED, "구독 상태별 공지는 대상 구독 상태가 필요합니다.");
      }
      audienceStatus = required("audienceStatus", body.audienceStatus(), AUDIENCE_STATUSES);
    } else if (body.audienceStatus() != null) {
      throw OpsQueryParams.invalid(
          "audienceStatus", FieldErrorCode.NOT_ALLOWED, "구독 상태별 공지에만 대상 구독 상태를 보냅니다.");
    }
    if (audienceType.equals("OFFICE")) {
      if (body.audienceOfficeId() == null) {
        throw OpsQueryParams.invalid(
            "audienceOfficeId", FieldErrorCode.REQUIRED, "특정 사무소 공지는 대상 사무소가 필요합니다.");
      }
      audienceOfficeId = body.audienceOfficeId();
    } else if (body.audienceOfficeId() != null) {
      throw OpsQueryParams.invalid(
          "audienceOfficeId", FieldErrorCode.NOT_ALLOWED, "특정 사무소 공지에만 대상 사무소를 보냅니다.");
    }
    Instant startsAt = body.startsAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    Instant endsAt = body.endsAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    if (!endsAt.isAfter(startsAt)) {
      throw OpsQueryParams.invalid(
          "endsAt", FieldErrorCode.OUT_OF_RANGE, "노출 종료는 노출 시작보다 뒤여야 합니다.");
    }
    String detailUrl =
        body.detailUrl() == null || body.detailUrl().isBlank() ? null : body.detailUrl().strip();
    if (detailUrl != null
        && !(detailUrl.startsWith("https://")
            || (detailUrl.startsWith("/") && !detailUrl.startsWith("//")))) {
      throw OpsQueryParams.invalid(
          "detailUrl", FieldErrorCode.INVALID_FORMAT, "상세 링크는 https:// 또는 /로 시작해야 합니다.");
    }
    if (audienceOfficeId != null && !repository.officeExists(audienceOfficeId)) {
      throw notFound();
    }
    return new NewAnnouncement(
        body.title().strip(),
        body.content().strip(),
        versionNo,
        previousId,
        audienceType,
        audienceStatus,
        audienceOfficeId,
        startsAt,
        endsAt,
        importance,
        detailUrl,
        body.marketing(),
        operatorId,
        now);
  }

  private AnnouncementRow lockedAnnouncement(long id) {
    return repository.lockAnnouncement(id).orElseThrow(OpsContentService::notFound);
  }

  // 정책 문서

  @Transactional(readOnly = true)
  public OpsPolicyDocumentListResponse policies(String docType, String status, PageQuery page) {
    PolicyFilter filter =
        new PolicyFilter(
            OpsQueryParams.oneOf("docType", docType, DOC_TYPES),
            OpsQueryParams.oneOf("status", status, STATUSES));
    List<PolicyDocumentItem> items =
        repository.policies(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsContentService::toItem)
            .toList();
    return new OpsPolicyDocumentListResponse(
        items, page.page(), page.size(), repository.countPolicies(filter));
  }

  @Transactional
  public PolicyDocumentCreateResponse createPolicy(
      long operatorId, PolicyDocumentCreateRequest body) {
    String docType = required("docType", body.docType(), DOC_TYPES);
    String version = body.version().strip();
    Instant noticeAt = body.noticeAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    Instant effectiveAt = body.effectiveAt().toInstant().truncatedTo(ChronoUnit.MICROS);
    if (noticeAt.isAfter(effectiveAt)) {
      throw OpsQueryParams.invalid(
          "noticeAt", FieldErrorCode.OUT_OF_RANGE, "공지 시각은 시행 시각보다 늦을 수 없습니다.");
    }
    if (docType.equals("PRICING") && noticeAt.plus(PRICING_LEAD).isAfter(effectiveAt)) {
      throw OpsQueryParams.invalid(
          "noticeAt", FieldErrorCode.OUT_OF_RANGE, "요금 정책은 시행 30일 전까지 공지해야 합니다.");
    }
    advisoryLock.lockForTransaction("ops:policy-document:" + docType);
    if (repository.policyVersionExists(docType, version)) {
      throw new BusinessException(ErrorCode.DOCUMENT_VERSION_CONFLICT);
    }
    long id =
        repository.insertPolicy(
            new NewPolicy(
                docType,
                version,
                body.body().strip(),
                body.changeSummary().strip(),
                noticeAt,
                body.requiresConsent(),
                effectiveAt,
                operatorId));
    auditLog.insert(
        operatorId,
        "POLICY_DOCUMENT_CREATED",
        "POLICY_DOCUMENT",
        id,
        docType + " " + version,
        now());
    return new PolicyDocumentCreateResponse(
        id, docType, version, "DRAFT", toDisplayOffset(effectiveAt));
  }

  @Transactional
  public PolicyDocumentSubmitReviewResponse submitPolicyReview(long operatorId, long id) {
    PolicyRow row = repository.lockPolicy(id).orElseThrow(OpsContentService::notFound);
    requireStatus(row.status(), "DRAFT");
    repository.submitPolicyReview(id, operatorId);
    auditLog.insert(
        operatorId,
        "POLICY_DOCUMENT_REVIEW_SUBMITTED",
        "POLICY_DOCUMENT",
        id,
        row.docType() + " " + row.version(),
        now());
    return new PolicyDocumentSubmitReviewResponse(id, row.version(), "REVIEW", operatorId);
  }

  @Transactional
  public PolicyDocumentPublishResponse publishPolicy(long operatorId, long id) {
    PolicyRow row = repository.lockPolicy(id).orElseThrow(OpsContentService::notFound);
    requireStatus(row.status(), "REVIEW");
    Instant now = now();
    if (row.docType().equals("PRICING") && now.plus(PRICING_LEAD).isAfter(row.effectiveAt())) {
      throw OpsQueryParams.invalid(
          "effectiveAt", FieldErrorCode.OUT_OF_RANGE, "요금 정책은 시행 30일 전까지만 게시·예약할 수 있습니다.");
    }
    repository.publishPolicy(id, operatorId, now);
    auditLog.insert(
        operatorId,
        "POLICY_DOCUMENT_PUBLISHED",
        "POLICY_DOCUMENT",
        id,
        row.docType() + " " + row.version(),
        now);
    return new PolicyDocumentPublishResponse(id, row.version(), "PUBLISHED", toDisplayOffset(now));
  }

  // 회사 제공 정보

  @Transactional(readOnly = true)
  public CompanyServiceInfoResponse companyInfos(PageQuery page) {
    List<CompanyServiceInfoItem> items =
        repository.infos(page.size(), (long) page.page() * page.size()).stream()
            .map(OpsContentService::toItem)
            .toList();
    List<String> missing = new ArrayList<>();
    Optional<InfoRow> published = repository.latestPublishedInfo();
    if (published.isEmpty()) {
      missing.add(MISSING_PUBLISHED);
    }
    InfoRow basis = published.or(repository::latestInfo).orElse(null);
    if (basis == null || basis.inquiryDeliveryTestedAt() == null) {
      missing.add(MISSING_TEST);
    }
    if (basis == null || basis.reviewedByOperatorId() == null) {
      missing.add(MISSING_REVIEW);
    }
    return new CompanyServiceInfoResponse(
        items, missing.isEmpty(), missing, page.page(), page.size(), repository.countInfos());
  }

  @Transactional
  public CompanyServiceInfoCreateResponse createCompanyInfo(
      long operatorId, CompanyServiceInfoRequest body) {
    String email = body.supportEmail().strip();
    if (!EMAIL.matcher(email).matches()) {
      throw OpsQueryParams.invalid(
          "supportEmail", FieldErrorCode.INVALID_FORMAT, "지원 이메일 형식이 올바르지 않습니다.");
    }
    advisoryLock.lockForTransaction("ops:company-service-info");
    int versionNo = repository.nextInfoVersion();
    InfoRow value =
        new InfoRow(
            0,
            versionNo,
            body.businessName().strip(),
            body.representativeName().strip(),
            body.businessNumber().strip(),
            body.address().strip(),
            body.contactPhone().strip(),
            body.ecommerceNotice() == null || body.ecommerceNotice().isBlank()
                ? null
                : body.ecommerceNotice().strip(),
            email,
            body.supportHours().strip(),
            body.emergencyContact().strip(),
            body.privacyOfficer().strip(),
            body.privacyContact().strip(),
            body.privacyRequestPath().strip(),
            body.cancellationPath().strip(),
            null,
            null,
            null);
    long id = repository.insertInfo(versionNo, value);
    auditLog.insert(
        operatorId,
        "COMPANY_SERVICE_INFO_CREATED",
        "COMPANY_SERVICE_INFO",
        id,
        "버전 " + versionNo,
        now());
    return new CompanyServiceInfoCreateResponse(id, versionNo, null, null, null);
  }

  @Transactional
  public InquiryTestResponse recordInquiryTest(
      long operatorId, long infoId, InquiryTestRequest body) {
    String result = required("result", body.result(), TEST_RESULTS);
    InfoRow row = repository.lockInfo(infoId).orElseThrow(OpsContentService::notFound);
    Instant now = now();
    if (result.equals("SUCCESS")) {
      repository.recordInquiryTest(infoId, operatorId, now);
      row = repository.lockInfo(infoId).orElseThrow();
    }
    auditLog.insert(
        operatorId,
        "COMPANY_INQUIRY_TEST_RECORDED",
        "COMPANY_SERVICE_INFO",
        infoId,
        "버전 " + row.versionNo() + " " + result,
        now);
    return new InquiryTestResponse(
        infoId,
        toDisplayOffset(row.inquiryDeliveryTestedAt()),
        row.reviewedByOperatorId(),
        toDisplayOffset(row.publishedAt()));
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void requireStatus(String actual, String expected) {
    if (!actual.equals(expected)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private static String required(String field, String raw, Set<String> allowed) {
    String value = OpsQueryParams.oneOf(field, raw, allowed);
    if (value == null) {
      throw OpsQueryParams.invalid(field, FieldErrorCode.REQUIRED, field + " 값이 필요합니다.");
    }
    return value;
  }

  private static AnnouncementItem toItem(AnnouncementRow row) {
    return new AnnouncementItem(
        row.id(),
        row.title(),
        row.content(),
        row.versionNo(),
        row.previousVersionId(),
        row.audienceType(),
        row.audienceStatus(),
        row.audienceOfficeId(),
        toDisplayOffset(row.startsAt()),
        toDisplayOffset(row.endsAt()),
        row.importance(),
        row.detailUrl(),
        row.marketing(),
        row.status(),
        toDisplayOffset(row.scheduledAt()),
        toDisplayOffset(row.publishedAt()),
        toDisplayOffset(row.endedAt()),
        toDisplayOffset(row.createdAt()),
        row.createdByOperatorId(),
        row.reviewedByOperatorId());
  }

  private static PolicyDocumentItem toItem(PolicyRow row) {
    return new PolicyDocumentItem(
        row.id(),
        row.docType(),
        row.version(),
        row.status(),
        row.body(),
        row.changeSummary(),
        toDisplayOffset(row.noticeAt()),
        row.requiresConsent(),
        toDisplayOffset(row.effectiveAt()),
        toDisplayOffset(row.publishedAt()),
        row.reviewedByOperatorId(),
        row.publishedByOperatorId(),
        row.createdByOperatorId());
  }

  private static CompanyServiceInfoItem toItem(InfoRow row) {
    return new CompanyServiceInfoItem(
        row.id(),
        row.versionNo(),
        row.businessName(),
        row.representativeName(),
        row.businessNumber(),
        row.address(),
        row.contactPhone(),
        row.ecommerceNotice(),
        row.supportEmail(),
        row.supportHours(),
        row.emergencyContact(),
        row.privacyOfficer(),
        row.privacyContact(),
        row.privacyRequestPath(),
        row.cancellationPath(),
        toDisplayOffset(row.inquiryDeliveryTestedAt()),
        row.reviewedByOperatorId(),
        toDisplayOffset(row.publishedAt()));
  }

  private static BusinessException notFound() {
    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
  }
}
