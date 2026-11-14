package com.sairo.be.domain.visit.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum CoordinationStatus {
  TIME_COLLECTING,
  COORDINATABLE,
  FINAL_CONFIRMATION_REQUIRED,
  APPLYING,
  CONFIRMED,
  RESCHEDULE_REQUIRED,
  VISIT_COMPLETED,
  VISIT_INCOMPLETE,
  CANCELLED;

  private static final Map<CoordinationStatus, Set<CoordinationStatus>> TRANSITIONS =
      new EnumMap<>(CoordinationStatus.class);

  static {
    TRANSITIONS.put(TIME_COLLECTING, EnumSet.of(COORDINATABLE, CANCELLED));
    TRANSITIONS.put(
        COORDINATABLE, EnumSet.of(TIME_COLLECTING, FINAL_CONFIRMATION_REQUIRED, CANCELLED));
    TRANSITIONS.put(
        FINAL_CONFIRMATION_REQUIRED,
        EnumSet.of(
            TIME_COLLECTING,
            COORDINATABLE,
            APPLYING,
            VISIT_COMPLETED,
            VISIT_INCOMPLETE,
            CANCELLED));
    TRANSITIONS.put(
        APPLYING,
        EnumSet.of(
            FINAL_CONFIRMATION_REQUIRED, CONFIRMED, VISIT_COMPLETED, VISIT_INCOMPLETE, CANCELLED));
    TRANSITIONS.put(
        CONFIRMED, EnumSet.of(VISIT_COMPLETED, VISIT_INCOMPLETE, RESCHEDULE_REQUIRED, CANCELLED));
    TRANSITIONS.put(
        RESCHEDULE_REQUIRED, EnumSet.of(CONFIRMED, VISIT_COMPLETED, VISIT_INCOMPLETE, CANCELLED));
    TRANSITIONS.put(VISIT_COMPLETED, EnumSet.of(VISIT_INCOMPLETE));
    TRANSITIONS.put(VISIT_INCOMPLETE, EnumSet.of(VISIT_COMPLETED));
    TRANSITIONS.put(CANCELLED, EnumSet.noneOf(CoordinationStatus.class));
  }

  public boolean canMoveTo(CoordinationStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean acceptsCustomerResponse() {
    return this == TIME_COLLECTING
        || this == COORDINATABLE
        || this == FINAL_CONFIRMATION_REQUIRED
        || this == RESCHEDULE_REQUIRED;
  }

  public boolean acceptsCandidateAddition() {
    return this == TIME_COLLECTING
        || this == COORDINATABLE
        || this == FINAL_CONFIRMATION_REQUIRED
        || this == APPLYING;
  }

  public boolean isFinished() {
    return this == VISIT_COMPLETED || this == VISIT_INCOMPLETE || this == CANCELLED;
  }

  public boolean acceptsCandidateRemoval() {
    return this == TIME_COLLECTING;
  }
}
