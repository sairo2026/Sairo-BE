package com.sairo.be.domain.office.entity;

import java.util.Set;

public enum RegistrationStatus {
  DRAFT,
  PENDING_REVIEW,
  SUPPLEMENT_REQUESTED,
  APPROVED,
  REJECTED,
  REVOKED,
  CANCELLED;

  public static final Set<RegistrationStatus> IN_PROGRESS =
      Set.of(DRAFT, PENDING_REVIEW, SUPPLEMENT_REQUESTED);

  public boolean isInProgress() {
    return IN_PROGRESS.contains(this);
  }
}
