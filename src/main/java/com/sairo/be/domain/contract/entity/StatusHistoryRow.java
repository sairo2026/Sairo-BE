package com.sairo.be.domain.contract.entity;

import java.time.Instant;

public record StatusHistoryRow(
    Long id,
    String track,
    String fromStatus,
    String toStatus,
    String reason,
    Long changedByMembershipId,
    Instant effectiveAt,
    Long supersedesHistoryId,
    Instant createdAt) {}
