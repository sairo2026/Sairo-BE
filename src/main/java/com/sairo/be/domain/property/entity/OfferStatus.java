package com.sairo.be.domain.property.entity;

public enum OfferStatus {
  AVAILABLE,
  NEGOTIATING,
  COMPLETED,
  WITHDRAWN;

  public boolean isActive() {
    return this == AVAILABLE || this == NEGOTIATING;
  }
}
