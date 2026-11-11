package com.sairo.be.domain.visit.service;

public final class ScheduleLockKeys {

  private ScheduleLockKeys() {}

  public static String manager(Long officeId, Long membershipId) {
    return "visit:" + officeId + ":manager:" + membershipId;
  }

  public static String customer(Long officeId, Long finalCustomerId) {
    return "visit:" + officeId + ":customer:" + finalCustomerId;
  }

  public static String property(Long propertyOfficeId, Long propertyId) {
    return "visit:" + propertyOfficeId + ":property:" + propertyId;
  }
}
