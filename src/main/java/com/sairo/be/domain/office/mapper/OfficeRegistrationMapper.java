package com.sairo.be.domain.office.mapper;

import com.sairo.be.domain.office.dto.response.OfficeRegistrationCancelResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationCreateResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationEvidenceResponse;
import com.sairo.be.domain.office.dto.response.OfficeRegistrationStatusResponse;
import com.sairo.be.domain.office.dto.response.ReapplicationStartResponse;
import com.sairo.be.domain.office.entity.OfficeRegistration;
import com.sairo.be.domain.office.entity.RegistrationStatus;
import com.sairo.be.global.support.TimeRules;

public final class OfficeRegistrationMapper {

  private OfficeRegistrationMapper() {}

  public static OfficeRegistrationCreateResponse toCreateResponse(
      OfficeRegistration registration, String warning) {
    return new OfficeRegistrationCreateResponse(
        registration.getId(),
        registration.getStatus().name(),
        registration.getCurrentRevisionNo(),
        TimeRules.toDisplayOffset(registration.getCreatedAt()),
        TimeRules.toDisplayOffset(registration.getUpdatedAt()),
        registration.getVersion(),
        warning);
  }

  public static OfficeRegistrationEvidenceResponse toEvidenceResponse(
      OfficeRegistration registration) {
    return new OfficeRegistrationEvidenceResponse(
        registration.getId(),
        registration.getStatus().name(),
        registration.getCurrentRevisionNo(),
        TimeRules.toDisplayOffset(registration.getCreatedAt()),
        TimeRules.toDisplayOffset(registration.getUpdatedAt()),
        registration.getVersion());
  }

  public static OfficeRegistrationStatusResponse toStatusResponse(OfficeRegistration registration) {
    RegistrationStatus status = registration.getStatus();
    boolean supplement = status == RegistrationStatus.SUPPLEMENT_REQUESTED;
    boolean rejected = status == RegistrationStatus.REJECTED;
    boolean revoked = status == RegistrationStatus.REVOKED;
    return new OfficeRegistrationStatusResponse(
        registration.getId(),
        status.name(),
        registration.getCurrentRevisionNo(),
        registration.getEntityType().name(),
        supplement ? registration.getSupplementReason() : null,
        supplement ? TimeRules.toDisplayOffset(registration.getSupplementDueAt()) : null,
        rejected ? registration.getRejectionReason() : null,
        rejected ? registration.isResolved() : null,
        revoked ? registration.getRevocationReason() : null,
        revoked ? TimeRules.toDisplayOffset(registration.getRevokedAt()) : null,
        TimeRules.toDisplayOffset(registration.getCreatedAt()),
        TimeRules.toDisplayOffset(registration.getUpdatedAt()),
        registration.getVersion());
  }

  public static OfficeRegistrationCancelResponse toCancelResponse(OfficeRegistration registration) {
    return new OfficeRegistrationCancelResponse(
        registration.getId(),
        registration.getStatus().name(),
        TimeRules.toDisplayOffset(registration.getCancelledAt()),
        TimeRules.toDisplayOffset(registration.getCreatedAt()),
        TimeRules.toDisplayOffset(registration.getUpdatedAt()),
        registration.getVersion());
  }

  public static ReapplicationStartResponse toReapplicationResponse(
      OfficeRegistration registration) {
    return new ReapplicationStartResponse(
        registration.getId(),
        registration.isResolved(),
        TimeRules.toDisplayOffset(registration.getCreatedAt()),
        TimeRules.toDisplayOffset(registration.getUpdatedAt()),
        registration.getVersion());
  }
}
