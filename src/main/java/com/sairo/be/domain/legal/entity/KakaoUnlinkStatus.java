package com.sairo.be.domain.legal.entity;

public enum KakaoUnlinkStatus {
  PENDING,
  SUCCEEDED,
  FAILED_RETRYING,
  ABANDONED,
  APPLIED,
  NO_MATCH,
  DUPLICATE_IGNORED
}
