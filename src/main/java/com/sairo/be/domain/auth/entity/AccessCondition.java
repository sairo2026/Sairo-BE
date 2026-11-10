package com.sairo.be.domain.auth.entity;

public enum AccessCondition {
  PRE_VERIFICATION,
  OFFICE_UNDER_REVIEW,
  VERIFIED_UNPAID,
  ACTIVE,
  PAYMENT_OVERDUE,
  AUTH_REVOKED,
  REMOVED,
  RETURN_EXPIRED
}
