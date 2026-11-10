package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.office.entity.EvidenceType;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembership;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.entity.OfficeRegistration;
import com.sairo.be.domain.office.entity.OfficeRequestSource;
import com.sairo.be.domain.office.entity.RegistrationActor;
import com.sairo.be.domain.office.entity.RegistrationStatus;
import com.sairo.be.domain.office.repository.OfficeMembershipHistoryRepository;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRegistrationRecordRepository;
import com.sairo.be.domain.office.repository.OfficeRegistrationRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.domain.office.service.MemberSessionRefreshService;
import com.sairo.be.domain.ops.dto.request.OfficeRegistrationReviewChecklistRequest;
import com.sairo.be.domain.ops.dto.request.OpsOfficeRevokeRequest;
import com.sairo.be.domain.ops.dto.request.OpsRegistrationRejectRequest;
import com.sairo.be.domain.ops.dto.request.OpsRegistrationSupplementRequest;
import com.sairo.be.domain.ops.dto.response.EvidenceDownloadUrlResponse;
import com.sairo.be.domain.ops.dto.response.OfficeRegistrationReviewChecklistResponse;
import com.sairo.be.domain.ops.dto.response.OperatorRegistrationDetailResponse;
import com.sairo.be.domain.ops.dto.response.OperatorRegistrationListItemResponse;
import com.sairo.be.domain.ops.dto.response.OpsOfficeRevokeResponse;
import com.sairo.be.domain.ops.dto.response.OpsRegistrationResponse;
import com.sairo.be.domain.ops.dto.response.ReviewChecklistItemResponse;
import com.sairo.be.domain.ops.entity.ReviewItemCode;
import com.sairo.be.domain.ops.mapper.OpsReviewMapper;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.RegistrationReviewCheckRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.StatusFilter;
import com.sairo.be.global.support.TimeRules;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OperatorRegistrationReviewService {

  private static final Duration DOWNLOAD_URL_TIME_TO_LIVE = Duration.ofMinutes(5);

  private final OfficeRegistrationRepository registrationRepository;
  private final OfficeRegistrationRecordRepository recordRepository;
  private final RegistrationReviewCheckRepository checkRepository;
  private final OfficeRepository officeRepository;
  private final OfficeMembershipRepository membershipRepository;
  private final OfficeMembershipHistoryRepository membershipHistoryRepository;
  private final OpsAuditLogRepository auditLogRepository;
  private final MemberSessionRefreshService sessionRefreshService;
  private final ObjectStorage objectStorage;
  private final JdbcClient jdbcClient;
  private final Clock clock;

  public OperatorRegistrationReviewService(
      OfficeRegistrationRepository registrationRepository,
      OfficeRegistrationRecordRepository recordRepository,
      RegistrationReviewCheckRepository checkRepository,
      OfficeRepository officeRepository,
      OfficeMembershipRepository membershipRepository,
      OfficeMembershipHistoryRepository membershipHistoryRepository,
      OpsAuditLogRepository auditLogRepository,
      MemberSessionRefreshService sessionRefreshService,
      ObjectStorage objectStorage,
      JdbcClient jdbcClient,
      Clock clock) {
    this.registrationRepository = registrationRepository;
    this.recordRepository = recordRepository;
    this.checkRepository = checkRepository;
    this.officeRepository = officeRepository;
    this.membershipRepository = membershipRepository;
    this.membershipHistoryRepository = membershipHistoryRepository;
    this.auditLogRepository = auditLogRepository;
    this.sessionRefreshService = sessionRefreshService;
    this.objectStorage = objectStorage;
    this.jdbcClient = jdbcClient;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<OperatorRegistrationListItemResponse> list(
      String statusParam, PageQuery pageQuery) {
    Set<RegistrationStatus> statuses =
        StatusFilter.parse(
            statusParam, RegistrationStatus.class, EnumSet.of(RegistrationStatus.PENDING_REVIEW));
    return PageResponse.from(
        registrationRepository.findByStatusIn(
            statuses, pageQuery.toPageable(Sort.by(Sort.Direction.ASC, "createdAt", "id"))),
        OpsReviewMapper::toListItem);
  }

  @Transactional(readOnly = true)
  public OperatorRegistrationDetailResponse detail(Long registrationId) {
    OfficeRegistration registration = find(registrationId);
    List<ReviewChecklistItemResponse> checklist =
        OpsReviewMapper.toChecklist(
            checkRepository.find(registrationId, registration.getCurrentRevisionNo()));
    return OpsReviewMapper.toDetail(
        registration,
        recordRepository.revisions(registrationId),
        recordRepository.evidenceTypesByRevision(registrationId),
        checklist,
        recordRepository.history(registrationId));
  }

  @Transactional
  public OfficeRegistrationReviewChecklistResponse updateChecklist(
      long operatorId, Long registrationId, OfficeRegistrationReviewChecklistRequest request) {
    Set<ReviewItemCode> seen = EnumSet.noneOf(ReviewItemCode.class);
    for (var item : request.items()) {
      if (!seen.add(item.itemCode())) {
        throw BusinessException.withFieldErrors(
            ErrorCode.INVALID_REQUEST,
            List.of(
                new FieldErrorItem(
                    "items", FieldErrorCode.INVALID_VALUE, "같은 itemCode를 두 번 보낼 수 없습니다.")));
      }
    }
    OfficeRegistration registration = lock(registrationId);
    if (registration.getStatus() != RegistrationStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    int revisionNo = registration.getCurrentRevisionNo();
    request
        .items()
        .forEach(
            item ->
                checkRepository.upsert(
                    registrationId, revisionNo, item.itemCode(), item.checked(), operatorId, now));
    auditLogRepository.insert(
        operatorId,
        "OFFICE_REGISTRATION_CHECKLIST_UPDATED",
        "OFFICE_REGISTRATION",
        registrationId,
        null,
        now);
    return OpsReviewMapper.toChecklistResponse(
        registration,
        OpsReviewMapper.toChecklist(checkRepository.find(registrationId, revisionNo)));
  }

  @Transactional
  public OpsRegistrationResponse approve(long operatorId, Long registrationId) {
    OfficeRegistration registration = lock(registrationId);
    if (registration.getStatus() != RegistrationStatus.PENDING_REVIEW) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    List<ReviewChecklistItemResponse> checklist =
        OpsReviewMapper.toChecklist(
            checkRepository.find(registrationId, registration.getCurrentRevisionNo()));
    if (!OpsReviewMapper.canApprove(checklist)) {
      throw new BusinessException(ErrorCode.REVIEW_CHECKLIST_INCOMPLETE);
    }
    Instant now = clock.instant();
    Office office;
    try {
      office =
          officeRepository.saveAndFlush(
              Office.register(
                  registration.getRequestedOfficeName(),
                  registration.getEntityType(),
                  registration.getRegistrationAuthority(),
                  registration.getRealEstateLicenseNumber(),
                  registration.getBusinessRegistrationNumber(),
                  registration.getRequestedRepresentativeName(),
                  registration.getRequestedRepresentativePhone(),
                  registration.getRequestedAddress()));
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.OFFICE_ALREADY_EXISTS);
    }
    OfficeMembership owner;
    try {
      owner =
          membershipRepository.saveAndFlush(
              OfficeMembership.approve(
                  registration.getApplicantUserId(),
                  office.getId(),
                  OfficeMembershipRole.OWNER,
                  OfficeRequestSource.REGISTRATION));
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    membershipHistoryRepository.insert(
        owner.getId(),
        null,
        OfficeMembershipStatus.APPROVED,
        OfficeMembershipHistoryRepository.Actor.SYSTEM,
        null,
        "사무소 인증 승인",
        now);
    registration.approve(office.getId(), now);
    registrationRepository.saveAndFlush(registration);
    recordRepository.insertHistory(
        registrationId,
        RegistrationStatus.PENDING_REVIEW,
        RegistrationStatus.APPROVED,
        RegistrationActor.OPERATOR,
        operatorId,
        null,
        now);
    auditLogRepository.insert(
        operatorId,
        "OFFICE_REGISTRATION_APPROVED",
        "OFFICE_REGISTRATION",
        registrationId,
        null,
        now);
    sessionRefreshService.refreshAfterCommit(registration.getApplicantUserId());
    return OpsReviewMapper.toRegistrationResponse(registration);
  }

  @Transactional
  public OpsRegistrationResponse requestSupplement(
      long operatorId, Long registrationId, OpsRegistrationSupplementRequest request) {
    Instant now = clock.instant();
    Instant dueAt = request.supplementDueAt().toInstant();
    if (!dueAt.isAfter(now)) {
      throw BusinessException.withFieldErrors(
          ErrorCode.INVALID_REQUEST,
          List.of(
              new FieldErrorItem(
                  "supplementDueAt", FieldErrorCode.OUT_OF_RANGE, "보완 기한은 요청 시각 이후여야 합니다.")));
    }
    OfficeRegistration registration = lock(registrationId);
    RegistrationStatus from = registration.getStatus();
    registration.requestSupplement(request.reason().strip(), dueAt, now);
    registrationRepository.saveAndFlush(registration);
    recordRepository.insertHistory(
        registrationId,
        from,
        RegistrationStatus.SUPPLEMENT_REQUESTED,
        RegistrationActor.OPERATOR,
        operatorId,
        request.reason().strip(),
        now);
    auditLogRepository.insert(
        operatorId,
        "OFFICE_REGISTRATION_SUPPLEMENT_REQUESTED",
        "OFFICE_REGISTRATION",
        registrationId,
        null,
        now);
    return OpsReviewMapper.toRegistrationResponse(registration);
  }

  @Transactional
  public OpsRegistrationResponse reject(
      long operatorId, Long registrationId, OpsRegistrationRejectRequest request) {
    OfficeRegistration registration = lock(registrationId);
    RegistrationStatus from = registration.getStatus();
    Instant now = clock.instant();
    registration.reject(request.reason().strip(), now);
    registrationRepository.saveAndFlush(registration);
    recordRepository.insertHistory(
        registrationId,
        from,
        RegistrationStatus.REJECTED,
        RegistrationActor.OPERATOR,
        operatorId,
        request.reason().strip(),
        now);
    auditLogRepository.insert(
        operatorId,
        "OFFICE_REGISTRATION_REJECTED",
        "OFFICE_REGISTRATION",
        registrationId,
        null,
        now);
    return OpsReviewMapper.toRegistrationResponse(registration);
  }

  @Transactional
  public EvidenceDownloadUrlResponse issueDownloadUrl(
      long operatorId, Long registrationId, EvidenceType evidenceType) {
    OfficeRegistration registration = find(registrationId);
    if (registration.getStatus() != RegistrationStatus.PENDING_REVIEW
        && registration.getStatus() != RegistrationStatus.SUPPLEMENT_REQUESTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    String key =
        recordRepository
            .evidenceKeys(registrationId, registration.getCurrentRevisionNo())
            .get(evidenceType);
    if (key == null) {
      throw new BusinessException(ErrorCode.EVIDENCE_NOT_FOUND);
    }
    Instant now = clock.instant();
    URI url = objectStorage.issueDownloadUrl(key, DOWNLOAD_URL_TIME_TO_LIVE);
    auditLogRepository.insert(
        operatorId,
        "OFFICE_EVIDENCE_DOWNLOAD_URL_ISSUED",
        "OFFICE_REGISTRATION",
        registrationId,
        evidenceType.name(),
        now);
    return new EvidenceDownloadUrlResponse(
        url.toString(), TimeRules.toDisplayOffset(now.plus(DOWNLOAD_URL_TIME_TO_LIVE)));
  }

  @Transactional
  public OpsOfficeRevokeResponse revokeOffice(
      long operatorId, Long officeId, OpsOfficeRevokeRequest request) {
    OfficeRegistration registration =
        registrationRepository
            .findByResultingOfficeIdForUpdate(officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    Instant now = clock.instant();
    RegistrationStatus from = registration.getStatus();
    registration.revoke(request.reason().strip(), now);
    registrationRepository.saveAndFlush(registration);
    recordRepository.insertHistory(
        registration.getId(),
        from,
        RegistrationStatus.REVOKED,
        RegistrationActor.OPERATOR,
        operatorId,
        request.reason().strip(),
        now);
    auditLogRepository.insert(operatorId, "OFFICE_REVOKED", "OFFICE", officeId, null, now);
    List<Long> memberUserIds =
        jdbcClient
            .sql(
                "SELECT DISTINCT user_id FROM office_membership WHERE office_id = :id"
                    + " AND status IN ('APPROVED','SUSPENDED')")
            .param("id", officeId)
            .query(Long.class)
            .list();
    sessionRefreshService.terminateAfterCommit(memberUserIds);
    return new OpsOfficeRevokeResponse(
        officeId, registration.getId(), registration.getStatus().name());
  }

  private OfficeRegistration find(Long registrationId) {
    return registrationRepository
        .findById(registrationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private OfficeRegistration lock(Long registrationId) {
    return registrationRepository
        .findByIdForUpdate(registrationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }
}
