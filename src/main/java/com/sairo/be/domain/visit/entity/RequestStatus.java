package com.sairo.be.domain.visit.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RequestStatus {
  PENDING,
  SELECTED,
  CONFIRMED,
  REVALIDATION_FAILED,
  TIME_CLOSED,
  EXPIRED,
  WITHDRAWN,
  CANCELLED;

  public static final Set<RequestStatus> ACTIVE = EnumSet.of(PENDING, SELECTED);

  private static final Map<RequestStatus, Set<RequestStatus>> TRANSITIONS =
      new EnumMap<>(RequestStatus.class);

  static {
    TRANSITIONS.put(PENDING, EnumSet.of(SELECTED, TIME_CLOSED, EXPIRED, WITHDRAWN));
    TRANSITIONS.put(SELECTED, EnumSet.of(CONFIRMED, REVALIDATION_FAILED, TIME_CLOSED, WITHDRAWN));
    TRANSITIONS.put(CONFIRMED, EnumSet.of(CANCELLED));
    TRANSITIONS.put(REVALIDATION_FAILED, EnumSet.noneOf(RequestStatus.class));
    TRANSITIONS.put(TIME_CLOSED, EnumSet.noneOf(RequestStatus.class));
    TRANSITIONS.put(EXPIRED, EnumSet.noneOf(RequestStatus.class));
    TRANSITIONS.put(WITHDRAWN, EnumSet.noneOf(RequestStatus.class));
    TRANSITIONS.put(CANCELLED, EnumSet.noneOf(RequestStatus.class));
  }

  public boolean canMoveTo(RequestStatus next) {
    return TRANSITIONS.get(this).contains(next);
  }

  public boolean isActive() {
    return ACTIVE.contains(this);
  }

  public boolean isClosed() {
    return this == REVALIDATION_FAILED
        || this == TIME_CLOSED
        || this == EXPIRED
        || this == WITHDRAWN
        || this == CANCELLED;
  }
}
