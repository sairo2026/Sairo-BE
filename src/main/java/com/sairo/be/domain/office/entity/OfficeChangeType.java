package com.sairo.be.domain.office.entity;

import java.util.Set;

public enum OfficeChangeType {
  ADDRESS(Set.of("address")),
  REGISTRATION_INFO(
      Set.of(
          "registrationAuthority",
          "realEstateLicenseNumber",
          "businessRegistrationNumber",
          "entityType")),
  REPRESENTATIVE(Set.of("representativeName", "representativePhone"));

  private final Set<String> allowedKeys;

  OfficeChangeType(Set<String> allowedKeys) {
    this.allowedKeys = allowedKeys;
  }

  public Set<String> allowedKeys() {
    return allowedKeys;
  }
}
