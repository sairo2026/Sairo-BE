package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.response.OfficeRegistrationEvidenceResponse;
import com.sairo.be.domain.office.entity.EvidenceType;
import com.sairo.be.domain.office.entity.OfficeRegistration;
import com.sairo.be.domain.office.entity.RegistrationActor;
import com.sairo.be.domain.office.entity.RegistrationStatus;
import com.sairo.be.domain.office.mapper.OfficeRegistrationMapper;
import com.sairo.be.domain.office.repository.OfficeRegistrationRecordRepository;
import com.sairo.be.domain.office.repository.OfficeRegistrationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class EvidenceCommitService {

  private final OfficeRegistrationRepository registrationRepository;
  private final OfficeRegistrationRecordRepository recordRepository;
  private final Clock clock;

  public EvidenceCommitService(
      OfficeRegistrationRepository registrationRepository,
      OfficeRegistrationRecordRepository recordRepository,
      Clock clock) {
    this.registrationRepository = registrationRepository;
    this.recordRepository = recordRepository;
    this.clock = clock;
  }

  @Transactional
  public OfficeRegistrationEvidenceResponse commit(
      Long userId, Long registrationId, Map<EvidenceType, String> uploaded) {
    OfficeRegistration registration =
        registrationRepository
            .findByIdForUpdate(registrationId)
            .filter(found -> found.getApplicantUserId().equals(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    RegistrationStatus from = registration.getStatus();
    int previousRevisionNo = registration.getCurrentRevisionNo();
    int revisionNo = registration.submitEvidence();
    Instant now = clock.instant();

    Map<EvidenceType, String> keys = new EnumMap<>(EvidenceType.class);
    if (from == RegistrationStatus.SUPPLEMENT_REQUESTED) {
      keys.putAll(recordRepository.evidenceKeys(registrationId, previousRevisionNo));
    }
    keys.putAll(uploaded);

    long revisionId =
        recordRepository.insertRevision(
            registrationId,
            revisionNo,
            registration.getRequestedOfficeName(),
            registration.getRequestedAddress(),
            now);
    keys.forEach((type, key) -> recordRepository.insertEvidence(revisionId, type, key, now));
    registrationRepository.saveAndFlush(registration);
    recordRepository.insertHistory(
        registrationId,
        from,
        RegistrationStatus.PENDING_REVIEW,
        RegistrationActor.APPLICANT,
        null,
        null,
        now);
    return OfficeRegistrationMapper.toEvidenceResponse(registration);
  }
}
