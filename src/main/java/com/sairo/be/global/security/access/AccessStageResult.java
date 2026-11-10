package com.sairo.be.global.security.access;

import com.sairo.be.global.error.ErrorCode;

public record AccessStageResult(boolean allowed, ErrorCode errorCode) {

  private static final AccessStageResult ALLOWED = new AccessStageResult(true, null);

  public static AccessStageResult allow() {
    return ALLOWED;
  }

  public static AccessStageResult deny(ErrorCode errorCode) {
    return new AccessStageResult(false, errorCode);
  }
}
