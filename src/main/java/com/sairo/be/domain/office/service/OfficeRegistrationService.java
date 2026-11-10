package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.request.OfficeLookupRequest;
import com.sairo.be.domain.office.dto.request.OfficeRegistrationCancelRequest;
import com.sairo.be.domain.office.dto.request.OfficeRegistrationCreateRequest;
import com.sairo.be.domain.office.dto.response.OfficeLookupResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationCancelResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationCreateResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationStatusResponse;
import com.sairo.be.domain.office.dto.response.ReapplicationStartResponse;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.entity.OfficeRegistration;
import com.sairo.be.domain.office.entity.RegistrationActor;
import com.sairo.be.domain.office.entity.RegistrationStatus;
import com.sairo.be.domain.office.mapper.OfficeRegistrationMapper;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRegistrationRecordRepository;
import com.sairo.be.domain.office.repository.OfficeRegistrationRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OfficeRegistrationService {

  private static final Set<OfficeMembershipStatus> OPEN_MEMBERSHIP =
      Set.of(OfficeMembershipStatus.APPROVED, OfficeMembershipStatus.PENDING);

  private final OfficeRepository officeRepository;
  private final OfficeRegistrationRepository registrationRepository;
  private final OfficeRegistrationRecordRepository recordRepository;
  private final OfficeMembershipRepository membershipRepository;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public OfficeRegistrationService(
      OfficeRepository officeRepository,
      OfficeRegistrationRepository registrationRepository,
      OfficeRegistrationRecordRepository recordRepository,
      OfficeMembershipRepository membershipRepository,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.officeRepository = officeRepository;
    this.registrationRepository = registrationRepository;
    this.recordRepository = recordRepository;
    this.membershipRepository = membershipRepository;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public OfficeLookupResponse lookup(OfficeLookupRequest request) {
    Long existing =
        officeRepository
            .findByRegistrationAuthorityAndRealEstateLicenseNumber(
                request.registrationAuthority().strip(), request.realEstateLicenseNumber().strip())
            .map(office -> office.getId())
            .orElse(null);
    return new OfficeLookupResponse(existing);
  }

  @Transactional
  public OfficeRegistrationCreateResponse create(
      Long userId, OfficeRegistrationCreateRequest request) {
    String businessNumber =
        OfficeInputRules.businessNumber(
            "businessRegistrationNumber", request.businessRegistrationNumber());
    String phone =
        OfficeInputRules.phone(
            "requestedRepresentativePhone", request.requestedRepresentativePhone());
    String authority = request.registrationAuthority().strip();
    String license = request.realEstateLicenseNumber().strip();

    advisoryLock.lockForTransaction("office-registration:user:" + userId);
    advisoryLock.lockForTransaction("office-registration:business:" + businessNumber);

    if (officeRepository
        .findByRegistrationAuthorityAndRealEstateLicenseNumber(authority, license)
        .isPresent()) {
      throw new BusinessException(ErrorCode.OFFICE_ALREADY_EXISTS);
    }
    if (registrationRepository.existsByApplicantUserIdAndStatusIn(
            userId, RegistrationStatus.IN_PROGRESS)
        || registrationRepository.existsByBusinessRegistrationNumberAndStatusIn(
            businessNumber, RegistrationStatus.IN_PROGRESS)
        || membershipRepository.existsByUserIdAndStatusIn(userId, OPEN_MEMBERSHIP)) {
      throw new BusinessException(ErrorCode.PENDING_REGISTRATION_EXISTS);
    }

    OfficeRegistration registration =
        registrationRepository.saveAndFlush(
            OfficeRegistration.draft(
                new OfficeRegistration.Draft(
                    userId,
                    request.entityType(),
                    authority,
                    license,
                    businessNumber,
                    request.requestedOfficeName().strip(),
                    request.requestedRepresentativeName().strip(),
                    phone,
                    request.requestedAddress().strip(),
                    request.applicantRegistrationRelation().strip())));
    recordRepository.insertHistory(
        registration.getId(),
        null,
        RegistrationStatus.DRAFT,
        RegistrationActor.APPLICANT,
        null,
        null,
        clock.instant());
    String warning =
        officeRepository.existsByBusinessRegistrationNumber(businessNumber)
            ? "DUPLICATE_BUSINESS_NUMBER"
            : null;
    return OfficeRegistrationMapper.toCreateResponse(registration, warning);
  }

  @Transactional(readOnly = true)
  public OfficeRegistrationStatusResponse latest(Long userId) {
    return registrationRepository
        .findFirstByApplicantUserIdOrderByIdDesc(userId)
        .map(OfficeRegistrationMapper::toStatusResponse)
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @Transactional
  public OfficeRegistrationCancelResponse cancel(
      Long userId, Long registrationId, OfficeRegistrationCancelRequest request) {
    OfficeRegistration registration = lockOwned(userId, registrationId);
    RegistrationStatus from = registration.getStatus();
    Instant now = clock.instant();
    registration.cancel(now);
    registrationRepository.saveAndFlush(registration);
    String reason =
        request == null || request.reason() == null || request.reason().isBlank()
            ? null
            : request.reason().strip();
    recordRepository.insertHistory(
        registrationId,
        from,
        RegistrationStatus.CANCELLED,
        RegistrationActor.APPLICANT,
        null,
        reason,
        now);
    return OfficeRegistrationMapper.toCancelResponse(registration);
  }

  @Transactional
  public ReapplicationStartResponse startReapplication(Long userId, Long registrationId) {
    OfficeRegistration registration = lockOwned(userId, registrationId);
    Instant now = clock.instant();
    registration.resolveRejection(now);
    registrationRepository.saveAndFlush(registration);
    recordRepository.insertHistory(
        registrationId,
        RegistrationStatus.REJECTED,
        RegistrationStatus.REJECTED,
        RegistrationActor.APPLICANT,
        null,
        null,
        now);
    return OfficeRegistrationMapper.toReapplicationResponse(registration);
  }

  OfficeRegistration lockOwned(Long userId, Long registrationId) {
    OfficeRegistration registration =
        registrationRepository
            .findByIdForUpdate(registrationId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!registration.getApplicantUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return registration;
  }
}
