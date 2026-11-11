package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.ArchiveGrantSummary;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.EmploymentRosterSummary;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.ExecSummary;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OfficeMemberItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorOfficeDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OperatorOfficeItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.OpsHomeResponse;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.RegistrationHistoryItem;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.ReturnGrantSummary;
import com.sairo.be.domain.ops.dto.response.OpsConsoleResponses.SubscriptionSummary;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository.OfficeFilter;
import com.sairo.be.domain.ops.repository.OpsConsoleQueryRepository.OfficeRow;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OpsHomeService {

  static final int REVIEW_TARGET_BUSINESS_DAYS = 2;
  static final Duration PRIVACY_DEADLINE_WINDOW = Duration.ofDays(3);
  static final Set<String> REGISTRATION_STATUSES = Set.of("APPROVED", "REVOKED");
  static final Set<String> SUBSCRIPTION_STATUSES =
      Set.of(
          "PRE_PAYMENT",
          "TRIAL",
          "ACTIVE",
          "PAST_DUE_GRACE",
          "PAST_DUE_RESTRICTED",
          "SUSPENDED",
          "ENDED");

  private final OpsConsoleQueryRepository queries;
  private final Clock clock;

  public OpsHomeService(OpsConsoleQueryRepository queries, Clock clock) {
    this.queries = queries;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public OpsHomeResponse home(OperatorPrincipal principal) {
    Instant now = clock.instant();
    var counts = queries.queueCounts(now, now.plus(PRIVACY_DEADLINE_WINDOW));
    ExecSummary exec = null;
    if (principal.hasRole(OperatorRole.EXEC_VIEW)) {
      var subscriptions = queries.subscriptionCounts();
      Instant monthStart =
          TimeRules.startOfDisplayDay(TimeRules.displayDate(now).withDayOfMonth(1));
      exec =
          new ExecSummary(
              subscriptions.trial(),
              subscriptions.active(),
              subscriptions.pastDue(),
              subscriptions.ended(),
              queries.netCollected(monthStart, now),
              toDisplayOffset(now));
    }
    return new OpsHomeResponse(
        counts.pendingReview(),
        overdueReviewCount(now),
        counts.unassignedInquiry(),
        counts.paymentPending(),
        counts.refundFailed(),
        counts.migrationReviewPending(),
        counts.privacyDeadlineApproaching(),
        counts.deletionFailed(),
        counts.activeIncident(),
        exec);
  }

  /** 심사 대기가 된 날 다음 영업일부터 2영업일째 날(KST)이 지난 신청 수다. */
  private long overdueReviewCount(Instant now) {
    List<Instant> received = queries.pendingReviewReceivedTimes();
    if (received.isEmpty()) {
      return 0;
    }
    LocalDate today = TimeRules.displayDate(now);
    LocalDate earliest =
        received.stream().map(TimeRules::displayDate).min(LocalDate::compareTo).orElse(today);
    Map<LocalDate, Boolean> overrides = queries.businessDayOverrides(earliest, today);
    return received.stream()
        .filter(at -> today.isAfter(businessDayAfter(TimeRules.displayDate(at), overrides)))
        .count();
  }

  static LocalDate businessDayAfter(LocalDate start, Map<LocalDate, Boolean> overrides) {
    LocalDate day = start;
    int counted = 0;
    while (counted < REVIEW_TARGET_BUSINESS_DAYS) {
      day = day.plusDays(1);
      boolean weekday =
          day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
      if (overrides.getOrDefault(day, weekday)) {
        counted++;
      }
    }
    return day;
  }

  @Transactional(readOnly = true)
  public PageResponse<OperatorOfficeItem> offices(
      String keyword, String registrationStatus, String subscriptionStatus, PageQuery page) {
    OfficeFilter filter =
        new OfficeFilter(
            OpsQueryParams.keyword(keyword),
            OpsQueryParams.oneOf("registrationStatus", registrationStatus, REGISTRATION_STATUSES),
            OpsQueryParams.oneOf("subscriptionStatus", subscriptionStatus, SUBSCRIPTION_STATUSES));
    List<OperatorOfficeItem> items =
        queries.offices(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(OpsHomeService::toItem)
            .toList();
    return PageResponse.of(items, page.page(), page.size(), queries.countOffices(filter));
  }

  @Transactional(readOnly = true)
  public OperatorOfficeDetailResponse office(long officeId) {
    OfficeRow office =
        queries
            .office(officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    LocalDate today = TimeRules.displayDate(clock.instant());
    Map<String, Long> roster = queries.rosterCounts(officeId, today);
    SubscriptionSummary subscription =
        queries
            .subscription(officeId)
            .map(
                row ->
                    new SubscriptionSummary(
                        row.status(),
                        row.plan(),
                        toDisplayOffset(row.trialEndsAt()),
                        toDisplayOffset(
                            row.currentPeriodEnd() != null
                                ? row.currentPeriodEnd()
                                : row.trialEndsAt()),
                        toDisplayOffset(row.pastDueSince())))
            .orElse(null);
    ReturnGrantSummary returnGrant =
        queries
            .latestReturnGrant(officeId)
            .map(
                grant ->
                    new ReturnGrantSummary(
                        grant.id(),
                        toDisplayOffset(grant.grantedAt()),
                        toDisplayOffset(grant.expiresAt()),
                        toDisplayOffset(grant.endedAt())))
            .orElse(null);
    ArchiveGrantSummary archiveGrant =
        queries
            .latestArchiveGrant(officeId)
            .map(
                grant ->
                    new ArchiveGrantSummary(
                        grant.id(),
                        toDisplayOffset(grant.grantedAt()),
                        toDisplayOffset(grant.expiresAt()),
                        toDisplayOffset(grant.endedAt())))
            .orElse(null);
    return new OperatorOfficeDetailResponse(
        office.id(),
        office.name(),
        office.representativeName(),
        office.businessRegistrationNumber(),
        office.realEstateLicenseNumber(),
        office.phone(),
        office.address(),
        toDisplayOffset(office.createdAt()),
        office.approvedMembershipCount(),
        office.registrationAuthority(),
        office.registrationStatus(),
        queries.registrationHistory(officeId).stream()
            .map(
                row ->
                    new RegistrationHistoryItem(
                        row.fromStatus(),
                        row.toStatus(),
                        row.changedByType(),
                        row.reason(),
                        toDisplayOffset(row.createdAt())))
            .toList(),
        queries.officeMembers(officeId).stream()
            .map(
                row ->
                    new OfficeMemberItem(
                        row.membershipId(), row.displayName(), row.role(), row.status()))
            .toList(),
        new EmploymentRosterSummary(
            roster.getOrDefault("AGENT", 0L), roster.getOrDefault("DEPUTY", 0L)),
        subscription,
        queries.openInquiryCount(officeId),
        returnGrant,
        archiveGrant,
        toDisplayOffset(office.updatedAt()),
        office.version());
  }

  private static OperatorOfficeItem toItem(OfficeRow row) {
    return new OperatorOfficeItem(
        row.id(),
        row.name(),
        row.businessRegistrationNumber(),
        row.realEstateLicenseNumber(),
        row.registrationAuthority(),
        row.address(),
        row.registrationStatus(),
        row.subscriptionStatus(),
        row.approvedMembershipCount(),
        toDisplayOffset(row.createdAt()),
        toDisplayOffset(row.updatedAt()),
        row.version());
  }
}
