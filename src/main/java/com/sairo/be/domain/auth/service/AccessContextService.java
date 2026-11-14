package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AccessCondition;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository.ApprovedRow;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository.InactiveMembershipRow;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository.OpenRegistrationRow;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository.RejectionRow;
import com.sairo.be.domain.auth.repository.AccessContextQueryRepository.SubscriptionRow;
import com.sairo.be.domain.auth.service.AccessJudgement.UnresolvedRejection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class AccessContextService {

  public static final Duration GRACE_PERIOD = Duration.ofDays(8);
  public static final Duration RETURN_PERIOD = Duration.ofDays(30);

  private final AccessContextQueryRepository queryRepository;
  private final Clock clock;

  public AccessContextService(AccessContextQueryRepository queryRepository, Clock clock) {
    this.queryRepository = queryRepository;
    this.clock = clock;
  }

  public AccessJudgement judge(Long userId) {
    Optional<ApprovedRow> approved = queryRepository.findApprovedMembership(userId);
    if (approved.isPresent()) {
      return judgeApproved(approved.get());
    }
    return judgeWithoutOffice(userId);
  }

  private AccessJudgement judgeApproved(ApprovedRow approved) {
    Long officeId = approved.officeId();
    String role = approved.role();
    if (queryRepository.isOfficeRegistrationRevoked(officeId)) {
      return approvedResult(AccessCondition.AUTH_REVOKED, officeId, role, null, null, "REVOKED");
    }
    Optional<SubscriptionRow> subscription = queryRepository.findSubscription(officeId);
    if (subscription.isEmpty() || "PRE_PAYMENT".equals(subscription.get().status())) {
      return approvedResult(AccessCondition.VERIFIED_UNPAID, officeId, role, null, null, null);
    }
    SubscriptionRow row = subscription.get();
    Instant now = clock.instant();
    return switch (row.status()) {
      case "PAST_DUE_GRACE" ->
          approvedResult(
              AccessCondition.ACTIVE,
              officeId,
              role,
              row.pastDueSince() == null ? null : row.pastDueSince().plus(GRACE_PERIOD),
              null,
              null);
      case "PAST_DUE_RESTRICTED" ->
          approvedResult(AccessCondition.PAYMENT_OVERDUE, officeId, role, null, null, null);
      case "SUSPENDED" ->
          approvedResult(
              AccessCondition.AUTH_REVOKED, officeId, role, null, null, "REVERIFICATION_SUSPENDED");
      case "ENDED" -> judgeEnded(officeId, role, row, now);
      default -> approvedResult(AccessCondition.ACTIVE, officeId, role, null, null, null);
    };
  }

  private AccessJudgement judgeEnded(Long officeId, String role, SubscriptionRow row, Instant now) {
    Instant returnEnds = row.endedAt() == null ? now : row.endedAt().plus(RETURN_PERIOD);
    if (now.isBefore(returnEnds)) {
      return approvedResult(
          AccessCondition.PAYMENT_OVERDUE, officeId, role, null, returnEnds, null);
    }
    return approvedResult(AccessCondition.RETURN_EXPIRED, officeId, role, null, null, null);
  }

  private AccessJudgement approvedResult(
      AccessCondition condition,
      Long officeId,
      String role,
      Instant graceUntil,
      Instant returnPeriodEndsAt,
      String restrictionReason) {
    return new AccessJudgement(
        condition,
        officeId,
        role,
        null,
        null,
        null,
        graceUntil,
        returnPeriodEndsAt,
        restrictionReason,
        null);
  }

  private AccessJudgement judgeWithoutOffice(Long userId) {
    Long pendingMembershipId = queryRepository.findPendingMembershipId(userId).orElse(null);
    Optional<OpenRegistrationRow> registration = queryRepository.findOpenRegistration(userId);
    Long pendingRegistrationId = registration.map(OpenRegistrationRow::id).orElse(null);
    UnresolvedRejection rejection =
        queryRepository
            .findUnresolvedRegistrationRejection(userId)
            .or(() -> queryRepository.findUnresolvedMembershipRejection(userId))
            .map(this::toRejection)
            .orElse(null);

    if (registration.isPresent() && !"DRAFT".equals(registration.get().status())) {
      return withoutOffice(
          AccessCondition.OFFICE_UNDER_REVIEW,
          pendingRegistrationId,
          pendingMembershipId,
          rejection,
          null);
    }
    if (registration.isPresent() || pendingMembershipId != null) {
      return withoutOffice(
          AccessCondition.PRE_VERIFICATION,
          pendingRegistrationId,
          pendingMembershipId,
          rejection,
          null);
    }
    Optional<InactiveMembershipRow> inactive = queryRepository.findLatestInactiveMembership(userId);
    if (inactive.isPresent() && rejection == null) {
      return withoutOffice(
          AccessCondition.REMOVED, null, null, null, removalReason(inactive.get()));
    }
    return withoutOffice(AccessCondition.PRE_VERIFICATION, null, null, rejection, null);
  }

  private String removalReason(InactiveMembershipRow row) {
    if ("SUSPENDED".equals(row.status())) {
      return "SUSPENDED";
    }
    return "SELF".equals(row.revokedByType()) ? "RESIGNED" : "REMOVED";
  }

  private UnresolvedRejection toRejection(RejectionRow row) {
    return new UnresolvedRejection(row.type(), row.reason());
  }

  private AccessJudgement withoutOffice(
      AccessCondition condition,
      Long pendingRegistrationId,
      Long pendingMembershipId,
      UnresolvedRejection rejection,
      String removalReason) {
    return new AccessJudgement(
        condition,
        null,
        null,
        pendingRegistrationId,
        pendingMembershipId,
        rejection,
        null,
        null,
        null,
        removalReason);
  }
}
