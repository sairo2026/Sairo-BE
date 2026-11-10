package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.response.OfficeRegistrationEvidenceResponse;
import com.sairo.be.domain.office.entity.EvidenceType;
import com.sairo.be.domain.office.entity.OfficeRegistration;
import com.sairo.be.domain.office.entity.RegistrationStatus;
import com.sairo.be.domain.office.repository.OfficeRegistrationRecordRepository;
import com.sairo.be.domain.office.repository.OfficeRegistrationRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.support.TokenGenerator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Profile("!migrate")
@Service
public class EvidenceSubmissionService {

  private final OfficeRegistrationRepository registrationRepository;
  private final OfficeRegistrationRecordRepository recordRepository;
  private final EvidenceFileValidator validator;
  private final EvidenceCommitService commitService;
  private final ObjectStorage objectStorage;
  private final TokenGenerator tokenGenerator;

  public EvidenceSubmissionService(
      OfficeRegistrationRepository registrationRepository,
      OfficeRegistrationRecordRepository recordRepository,
      EvidenceFileValidator validator,
      EvidenceCommitService commitService,
      ObjectStorage objectStorage,
      TokenGenerator tokenGenerator) {
    this.registrationRepository = registrationRepository;
    this.recordRepository = recordRepository;
    this.validator = validator;
    this.commitService = commitService;
    this.objectStorage = objectStorage;
    this.tokenGenerator = tokenGenerator;
  }

  public OfficeRegistrationEvidenceResponse submit(
      Long userId, Long registrationId, List<String> typeNames, List<MultipartFile> files) {
    OfficeRegistration registration =
        registrationRepository
            .findById(registrationId)
            .filter(found -> found.getApplicantUserId().equals(userId))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!registration.acceptsEvidence()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }

    List<EvidenceType> types = parseTypes(typeNames, files);
    requireComplete(registration, types);
    Map<EvidenceType, EvidenceFileValidator.ValidatedFile> validated =
        new EnumMap<>(EvidenceType.class);
    for (int i = 0; i < types.size(); i++) {
      validated.put(types.get(i), validator.validate(types.get(i), files.get(i)));
    }

    Map<EvidenceType, String> uploaded = new EnumMap<>(EvidenceType.class);
    try {
      validated.forEach(
          (type, file) -> {
            String key =
                "office-registration/%d/%s.%s"
                    .formatted(registrationId, tokenGenerator.randomToken(16), file.extension());
            objectStorage.put(key, file.content(), file.contentType());
            uploaded.put(type, key);
          });
      return commitService.commit(userId, registrationId, uploaded);
    } catch (RuntimeException e) {
      uploaded.values().forEach(objectStorage::delete);
      throw e;
    }
  }

  private List<EvidenceType> parseTypes(List<String> typeNames, List<MultipartFile> files) {
    if (typeNames == null
        || files == null
        || typeNames.isEmpty()
        || typeNames.size() != files.size()) {
      throw invalid("file", FieldErrorCode.INVALID_VALUE, "evidenceType과 파일은 한 쌍씩 보내야 합니다.");
    }
    List<EvidenceType> types = new ArrayList<>();
    Set<EvidenceType> seen = EnumSet.noneOf(EvidenceType.class);
    for (String name : typeNames) {
      EvidenceType type;
      try {
        type = EvidenceType.valueOf(name == null ? "" : name.strip());
      } catch (IllegalArgumentException e) {
        throw invalid("evidenceType", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 증빙 종류입니다.");
      }
      if (!seen.add(type)) {
        throw invalid(
            "evidenceType." + type.name(), FieldErrorCode.INVALID_VALUE, "같은 종류를 두 번 보낼 수 없습니다.");
      }
      types.add(type);
    }
    return types;
  }

  private void requireComplete(OfficeRegistration registration, List<EvidenceType> submitted) {
    Set<EvidenceType> present = EnumSet.noneOf(EvidenceType.class);
    present.addAll(submitted);
    if (registration.getStatus() == RegistrationStatus.SUPPLEMENT_REQUESTED) {
      present.addAll(
          recordRepository
              .evidenceKeys(registration.getId(), registration.getCurrentRevisionNo())
              .keySet());
    }
    List<FieldErrorItem> missing = new ArrayList<>();
    for (EvidenceType required : EvidenceType.requiredFor(registration.getEntityType())) {
      if (!present.contains(required)) {
        missing.add(
            new FieldErrorItem(
                "evidenceType." + required.name(), FieldErrorCode.REQUIRED, "필수 증빙이 빠졌습니다."));
      }
    }
    if (!missing.isEmpty()) {
      throw BusinessException.withFieldErrors(ErrorCode.INVALID_REQUEST, missing);
    }
  }

  private static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
