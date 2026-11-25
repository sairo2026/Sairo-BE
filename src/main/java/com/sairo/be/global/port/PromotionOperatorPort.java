package com.sairo.be.global.port;

import java.time.Instant;

/** Operator authorization and audit boundary for internal free promotions. */
public interface PromotionOperatorPort {
  boolean canManageBilling(long operatorId);

  void record(
      long operatorId, String action, String targetType, long targetId, String details, Instant at);
}
