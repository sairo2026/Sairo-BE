package com.sairo.be.domain.office.entity;

import java.util.EnumSet;
import java.util.Set;

public enum EvidenceType {
  BROKERAGE_LICENSE,
  BUSINESS_CERTIFICATE,
  REPRESENTATIVE_QUALIFICATION,
  CORPORATE_AUTHORITY,
  REGISTRATION_RELATION;

  public static Set<EvidenceType> requiredFor(OfficeEntityType entityType) {
    Set<EvidenceType> required =
        EnumSet.of(BROKERAGE_LICENSE, BUSINESS_CERTIFICATE, REPRESENTATIVE_QUALIFICATION);
    if (entityType == OfficeEntityType.CORPORATION) {
      required.add(CORPORATE_AUTHORITY);
      required.add(REGISTRATION_RELATION);
    }
    return required;
  }
}
