package com.sairo.be.domain.billing.entity;

public enum SubscriptionStatus {
  PRE_PAYMENT,
  TRIAL,
  ACTIVE,
  PAST_DUE_GRACE,
  PAST_DUE_RESTRICTED,
  SUSPENDED,
  ENDED
}
