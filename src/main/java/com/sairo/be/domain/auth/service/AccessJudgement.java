package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AccessCondition;
import java.time.Instant;

public record AccessJudgement(
    AccessCondition accessCondition,
    Long officeId,
    String role,
    Long pendingRegistrationId,
    Long pendingMembershipId,
    UnresolvedRejection unresolvedRejection,
    Instant graceUntil,
    Instant returnPeriodEndsAt,
    String restrictionReason,
    String removalReason) {

  public record UnresolvedRejection(String type, String reason) {}
}
