package com.sairo.be.domain.migration.entity;

public enum ImportSupportStatus {
  REQUESTED,
  QUOTED,
  ACCEPTED,
  PAID,
  PROCESSING,
  REVIEW,
  APPROVED,
  DELIVERED,
  CANCELLED,
  REJECTED;

  public boolean isActive() {
    return this != CANCELLED && this != REJECTED;
  }

  public boolean isCancellableByOwner() {
    return this == REQUESTED || this == QUOTED || this == ACCEPTED;
  }
}
