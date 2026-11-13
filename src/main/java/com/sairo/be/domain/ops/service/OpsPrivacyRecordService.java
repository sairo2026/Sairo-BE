package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.legal.entity.PrivacyRequestType;
import com.sairo.be.domain.legal.service.PrivacyRequestService;
import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.AuthorityRequestCloseRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.AuthorityRequestCreateRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.GuestPrivacyIntakeResolveRequest;
import com.sairo.be.domain.ops.dto.request.OpsPrivacyRecordRequests.ProviderContractCopyCreateRequest;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.AuthorityRequestItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.AuthorityRequestResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.GuestPrivacyIntakeItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.GuestPrivacyIntakeResolveResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.ProviderContractCopyItem;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.ProviderContractCopyListResponse;
import com.sairo.be.domain.ops.dto.response.OpsPrivacyRecordResponses.ProviderContractCopyResponse;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsPrivacyRecordRepository;
import com.sairo.be.domain.ops.repository.OpsPrivacyRecordRepository.AuthorityClose;
import com.sairo.be.domain.ops.repository.OpsPrivacyRecordRepository.AuthorityRow;
import com.sairo.be.domain.ops.repository.OpsPrivacyRecordRepository.ContractCopyRow;
import com.sairo.be.domain.ops.repository.OpsPrivacyRecordRepository.IntakeRow;
import com.sairo.be.domain.ops.repository.OpsPrivacyRecordRepository.NewAuthority;
import com.sairo.be.domain.ops.repository.OpsPrivacyRecordRepository.NewContractCopy;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.ContactMasking;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비회원 권리 요청 처리(OPS-15), 수사기관 등 자료 요청 기록(OPS-16), 공급자 계약서 사본 보관(OPS-17)이다. 권리 요청 전환은 legal의
 * PrivacyRequestService를 그대로 쓴다.
 */
@Profile("!migrate")
@Service
public class OpsPrivacyRecordService {

  static final Duration GUEST_LEGAL_DUE = Duration.ofDays(10);
  static final Duration GUEST_INTERNAL_DUE = Duration.ofDays(7);
  static final int AUTHORITY_RETENTION_DEFAULT_DAYS = 1825;
  static final int CONTRACT_COPY_RETENTION_DEFAULT_DAYS = 1825;
  static final Set<String> INTAKE_STATUSES = Set.of("RECEIVED", "CONVERTED", "CLOSED");
  static final Set<String> DECISIONS =
      Set.of("PENDING", "PROVIDED", "PARTIALLY_PROVIDED", "REFUSED", "FORWARDED_TO_OFFICE");
  private static final Set<String> PROVIDING = Set.of("PROVIDED", "PARTIALLY_PROVIDED");

  private final OpsPrivacyRecordRepository records;
  private final PrivacyRequestService privacyRequests;
  private final RetentionRuleLookup retentionRules;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final TextCipher cipher;
  private final Clock clock;

  public OpsPrivacyRecordService(
      OpsPrivacyRecordRepository records,
      PrivacyRequestService privacyRequests,
      RetentionRuleLookup retentionRules,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      TextCipher cipher,
      Clock clock) {
    this.records = records;
    this.privacyRequests = privacyRequests;
    this.retentionRules = retentionRules;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.cipher = cipher;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<GuestPrivacyIntakeItem> intakes(
      String status, Long officeId, PageQuery page) {
    String resolvedStatus = OpsQueryParams.oneOf("status", status, INTAKE_STATUSES);
    Long resolvedOffice = OpsQueryParams.positiveId("officeId", officeId);
    List<GuestPrivacyIntakeItem> items =
        records
            .intakes(resolvedStatus, resolvedOffice, page.size(), (long) page.page() * page.size())
            .stream()
            .map(
                row ->
                    new GuestPrivacyIntakeItem(
                        row.id(),
                        row.officeId(),
                        row.officeName(),
                        row.linkKind(),
                        row.requestType(),
                        row.requesterRole(),
                        row.contactChannel(),
                        maskedContact(row),
                        row.verificationStatus(),
                        row.status(),
                        toDisplayOffset(row.receivedAt()),
                        toDisplayOffset(row.internalDueAt()),
                        row.privacyRequestId()))
            .toList();
    return PageResponse.of(
        items, page.page(), page.size(), records.countIntakes(resolvedStatus, resolvedOffice));
  }

  @Transactional
  public GuestPrivacyIntakeResolveResponse resolveIntake(
      long operatorId, long intakeId, GuestPrivacyIntakeResolveRequest body) {
    boolean convert = body.action().equals("CONVERT");
    if (convert && body.verificationResult().equals("FAILED")) {
      throw OpsQueryParams.invalid(
          "action", FieldErrorCode.NOT_ALLOWED, "본인 확인에 실패하면 종결만 할 수 있습니다.");
    }
    String legalBasis = blankToNull(body.legalBasis());
    String scope = blankToNull(body.requestScope());
    if (convert && legalBasis == null) {
      throw OpsQueryParams.invalid("legalBasis", FieldErrorCode.REQUIRED, "전환하려면 법적 근거가 필요합니다.");
    }
    if (convert && scope == null) {
      throw OpsQueryParams.invalid("requestScope", FieldErrorCode.REQUIRED, "전환하려면 처리 범위가 필요합니다.");
    }
    if (!convert && body.closeReason() == null) {
      throw OpsQueryParams.invalid("closeReason", FieldErrorCode.REQUIRED, "종결하려면 사유가 필요합니다.");
    }
    IntakeRow intake =
        records
            .lockIntake(intakeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    switch (intake.status()) {
      case "CONVERTED" -> throw new BusinessException(ErrorCode.GUEST_INTAKE_ALREADY_CONVERTED);
      case "CLOSED" -> throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      default -> {}
    }
    requireActive(operatorId, OperatorRole.PRIVACY);
    Instant now = clock.instant();
    if (!convert) {
      records.closeIntake(intakeId, body.verificationResult(), body.closeReason(), now);
      auditLog.insert(
          operatorId,
          "GUEST_PRIVACY_INTAKE_CLOSED",
          "GUEST_PRIVACY_INTAKE",
          intakeId,
          "reason=" + body.closeReason() + " verification=" + body.verificationResult(),
          now);
      return new GuestPrivacyIntakeResolveResponse(
          intakeId, "CLOSED", body.verificationResult(), null, body.closeReason());
    }
    Instant legalDueAt =
        body.legalDueAt() == null
            ? intake.receivedAt().plus(GUEST_LEGAL_DUE)
            : body.legalDueAt().toInstant();
    Instant internalDueAt =
        body.internalDueAt() == null
            ? intake.receivedAt().plus(GUEST_INTERNAL_DUE)
            : body.internalDueAt().toInstant();
    if (internalDueAt.isBefore(intake.receivedAt())) {
      throw OpsQueryParams.invalid(
          "internalDueAt", FieldErrorCode.OUT_OF_RANGE, "내부 기한은 접수 시각 이후여야 합니다.");
    }
    if (internalDueAt.isAfter(legalDueAt)) {
      throw OpsQueryParams.invalid(
          "legalDueAt", FieldErrorCode.OUT_OF_RANGE, "법정 기한은 내부 기한 이후여야 합니다.");
    }
    Long requestId =
        privacyRequests.receiveFromGuestIntake(
            new PrivacyRequestService.GuestConversion(
                intakeId,
                intake.officeId(),
                PrivacyRequestType.valueOf(intake.requestType()),
                scope,
                legalBasis,
                intake.receivedAt(),
                legalDueAt,
                internalDueAt),
            operatorId,
            now);
    records.convertIntake(intakeId, body.verificationResult(), requestId);
    auditLog.insert(
        operatorId,
        "GUEST_PRIVACY_INTAKE_CONVERTED",
        "GUEST_PRIVACY_INTAKE",
        intakeId,
        "privacyRequestId=" + requestId,
        now);
    return new GuestPrivacyIntakeResolveResponse(
        intakeId, "CONVERTED", body.verificationResult(), requestId, null);
  }

  @Transactional(readOnly = true)
  public PageResponse<AuthorityRequestItem> authorityRequests(
      String decision, Boolean open, PageQuery page) {
    String resolvedDecision = OpsQueryParams.oneOf("decision", decision, DECISIONS);
    boolean openOnly = Boolean.TRUE.equals(open);
    List<AuthorityRequestItem> items =
        records
            .authorityRequests(
                resolvedDecision, openOnly, page.size(), (long) page.page() * page.size())
            .stream()
            .map(
                row ->
                    new AuthorityRequestItem(
                        row.id(),
                        toDisplayOffset(row.receivedAt()),
                        row.agencyType(),
                        row.agencyName(),
                        row.documentReference(),
                        row.decision(),
                        row.officeNoticeStatus(),
                        row.recordedByOperatorId(),
                        row.recordedByOperatorName(),
                        toDisplayOffset(row.closedAt()),
                        row.retentionUntil()))
            .toList();
    return PageResponse.of(
        items,
        page.page(),
        page.size(),
        records.countAuthorityRequests(resolvedDecision, openOnly));
  }

  @Transactional
  public AuthorityRequestResponse recordAuthorityRequest(
      long operatorId, AuthorityRequestCreateRequest body) {
    Instant now = clock.instant();
    Instant receivedAt = body.receivedAt().toInstant();
    if (receivedAt.isAfter(now)) {
      throw OpsQueryParams.invalid(
          "receivedAt", FieldErrorCode.OUT_OF_RANGE, "받은 시각은 현재 시각 이전이어야 합니다.");
    }
    if (body.targetOfficeId() != null && !records.officeExists(body.targetOfficeId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    if (body.targetUserId() != null && !records.userExists(body.targetUserId())) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    requireActive(operatorId, OperatorRole.PRIVACY);
    long id =
        records.insertAuthorityRequest(
            new NewAuthority(
                receivedAt,
                body.agencyType(),
                body.agencyName().strip(),
                blankToNull(body.documentReference()),
                body.legalBasisNote().strip(),
                body.targetOfficeId(),
                body.targetUserId(),
                body.requestedItems().strip(),
                operatorId),
            now);
    auditLog.insert(operatorId, "AUTHORITY_REQUEST_RECORDED", "AUTHORITY_REQUEST", id, null, now);
    return toAuthority(records.authorityRequest(id, false).orElseThrow());
  }

  /** 제공·일부 제공은 기록자와 다른 활성 보안관리자만 한다. 거절·사무소 이관 종결은 개인정보담당이나 보안관리자가 한다. 역할은 세션과 DB 둘 다 확인한다. */
  @Transactional
  public AuthorityRequestResponse closeAuthorityRequest(
      long operatorId, long requestId, AuthorityRequestCloseRequest body) {
    boolean providing = PROVIDING.contains(body.decision());
    String providedItems = blankToNull(body.providedItems());
    Instant now = clock.instant();
    Instant providedAt = body.providedAt() == null ? null : body.providedAt().toInstant();
    if (providing && providedItems == null) {
      throw OpsQueryParams.invalid(
          "providedItems", FieldErrorCode.REQUIRED, "제공 결정에는 제공 범위가 필요합니다.");
    }
    if (providing && providedAt == null) {
      throw OpsQueryParams.invalid("providedAt", FieldErrorCode.REQUIRED, "제공 결정에는 제공 시각이 필요합니다.");
    }
    if (!providing && (providedItems != null || providedAt != null)) {
      throw OpsQueryParams.invalid(
          providedItems != null ? "providedItems" : "providedAt",
          FieldErrorCode.NOT_ALLOWED,
          "제공하지 않은 결정에는 제공 범위와 시각을 적지 않습니다.");
    }
    if (providedAt != null && providedAt.isAfter(now)) {
      throw OpsQueryParams.invalid(
          "providedAt", FieldErrorCode.OUT_OF_RANGE, "제공 시각은 현재 시각 이전이어야 합니다.");
    }
    AuthorityRow row =
        records
            .authorityRequest(requestId, true)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (row.closedAt() != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (providing) {
      if (row.recordedByOperatorId() == operatorId) {
        throw new BusinessException(ErrorCode.SELF_APPROVAL_FORBIDDEN);
      }
      requireActive(operatorId, OperatorRole.SECURITY_ADMIN);
    } else if (!accounts.isActiveWithRole(operatorId, OperatorRole.PRIVACY)
        && !accounts.isActiveWithRole(operatorId, OperatorRole.SECURITY_ADMIN)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    LocalDate retentionUntil =
        TimeRules.displayDate(now)
            .plusDays(
                retentionRules
                    .retentionDays("AUTHORITY_REQUEST")
                    .orElse(AUTHORITY_RETENTION_DEFAULT_DAYS));
    String note = blankToNull(body.closeNote());
    records.closeAuthorityRequest(
        requestId,
        new AuthorityClose(
            body.decision(),
            providedItems,
            providedAt,
            body.officeNoticeStatus(),
            providing ? operatorId : null,
            now,
            retentionUntil,
            note));
    auditLog.insert(
        operatorId,
        "AUTHORITY_REQUEST_CLOSED",
        "AUTHORITY_REQUEST",
        requestId,
        "decision=" + body.decision(),
        now);
    return toAuthority(records.authorityRequest(requestId, false).orElseThrow());
  }

  @Transactional(readOnly = true)
  public ProviderContractCopyListResponse contractCopies(long integrationId) {
    if (!records.integrationExists(integrationId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return new ProviderContractCopyListResponse(
        records.contractCopies(integrationId).stream()
            .map(
                row ->
                    new ProviderContractCopyItem(
                        row.id(),
                        row.documentKind(),
                        row.versionLabel(),
                        row.signedOn(),
                        row.validUntil(),
                        row.contentSha256(),
                        toDisplayOffset(row.subprocessorChangeNotifiedAt()),
                        toDisplayOffset(row.uploadedAt())))
            .toList());
  }

  @Transactional
  public ProviderContractCopyResponse registerContractCopy(
      long operatorId, long integrationId, ProviderContractCopyCreateRequest body) {
    if (body.signedOn() != null
        && body.validUntil() != null
        && body.validUntil().isBefore(body.signedOn())) {
      throw OpsQueryParams.invalid(
          "validUntil", FieldErrorCode.OUT_OF_RANGE, "유효 종료일은 체결일 이후여야 합니다.");
    }
    if (!records.integrationExists(integrationId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    requireActive(operatorId, OperatorRole.PRIVACY);
    String label = body.versionLabel().strip();
    if (records.contractCopyExists(integrationId, body.documentKind(), label)) {
      throw new BusinessException(ErrorCode.DOCUMENT_VERSION_CONFLICT);
    }
    Instant now = clock.instant();
    LocalDate retentionUntil =
        body.validUntil() == null
            ? null
            : body.validUntil()
                .plusDays(
                    retentionRules
                        .retentionDays("PROVIDER_CONTRACT_COPY")
                        .orElse(CONTRACT_COPY_RETENTION_DEFAULT_DAYS));
    OffsetDateTime notified = body.subprocessorChangeNotifiedAt();
    ContractCopyRow row;
    try {
      row =
          records.insertContractCopy(
              new NewContractCopy(
                  integrationId,
                  body.documentKind(),
                  label,
                  body.signedOn(),
                  body.validUntil(),
                  body.storageKey().strip(),
                  body.contentSha256().toLowerCase(Locale.ROOT),
                  notified == null ? null : notified.toInstant(),
                  operatorId,
                  now,
                  retentionUntil));
    } catch (DuplicateKeyException e) {
      throw new BusinessException(ErrorCode.DOCUMENT_VERSION_CONFLICT);
    }
    auditLog.insert(
        operatorId,
        "PROVIDER_CONTRACT_COPY_REGISTERED",
        "INTEGRATION",
        integrationId,
        "copyId=" + row.id() + " documentKind=" + row.documentKind(),
        now);
    return new ProviderContractCopyResponse(
        row.id(),
        row.integrationId(),
        row.documentKind(),
        row.versionLabel(),
        row.signedOn(),
        row.validUntil(),
        row.contentSha256(),
        toDisplayOffset(row.subprocessorChangeNotifiedAt()),
        row.uploadedByOperatorId(),
        toDisplayOffset(row.uploadedAt()),
        row.retentionUntil());
  }

  private String maskedContact(IntakeRow row) {
    if (row.contactEncrypted() == null) {
      return null;
    }
    String contact = cipher.decrypt(row.contactEncrypted());
    return "PHONE".equals(row.contactChannel())
        ? ContactMasking.phone(contact)
        : ContactMasking.email(contact);
  }

  private void requireActive(long operatorId, OperatorRole role) {
    if (!accounts.isActiveWithRole(operatorId, role)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private static AuthorityRequestResponse toAuthority(AuthorityRow row) {
    return new AuthorityRequestResponse(
        row.id(),
        toDisplayOffset(row.receivedAt()),
        row.agencyType(),
        row.agencyName(),
        row.documentReference(),
        row.legalBasisNote(),
        row.targetOfficeId(),
        row.targetUserId(),
        row.requestedItems(),
        row.decision(),
        row.providedItems(),
        toDisplayOffset(row.providedAt()),
        row.officeNoticeStatus(),
        row.recordedByOperatorId(),
        row.approvedByOperatorId(),
        toDisplayOffset(row.closedAt()),
        row.retentionUntil(),
        row.closeNote());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
