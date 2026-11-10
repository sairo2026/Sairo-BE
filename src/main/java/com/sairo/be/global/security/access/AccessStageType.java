package com.sairo.be.global.security.access;

import com.sairo.be.global.error.ErrorCode;

public enum AccessStageType {
  LOGIN(ErrorCode.UNAUTHENTICATED),
  OFFICE_BOUNDARY(ErrorCode.RESOURCE_NOT_FOUND),
  MEMBERSHIP_STATUS(ErrorCode.FORBIDDEN),
  VERIFICATION_STATE(ErrorCode.FORBIDDEN),
  SUBSCRIPTION_STATE(ErrorCode.FORBIDDEN),
  ROLE_PERMISSION(ErrorCode.FORBIDDEN),
  TARGET_DATA_STATE(ErrorCode.INVALID_TRANSITION);

  private final ErrorCode defaultDenial;

  AccessStageType(ErrorCode defaultDenial) {
    this.defaultDenial = defaultDenial;
  }

  public ErrorCode defaultDenial() {
    return defaultDenial;
  }
}
