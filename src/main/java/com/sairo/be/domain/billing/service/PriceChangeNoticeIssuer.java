package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.domain.billing.service.PriceCalculator.PriceTable;
import com.sairo.be.domain.billing.service.PriceScheduleSelector.PriceSelection;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가격 변경 안내(pricing_change_notice)를 만들고 대표 알림함에 PLAN_CHANGED로 알린다. 같은 구독·새 가격 버전 안내는 유일 제약으로 한 건뿐이라
 * 다시 요청해도 중복 안내와 중복 알림이 생기지 않는다.
 */
@Profile("!migrate")
@Service
public class PriceChangeNoticeIssuer {

  public static final Duration NOTICE_PERIOD = Duration.ofDays(30);
  static final Set<SubscriptionStatus> PAYING =
      EnumSet.of(
          SubscriptionStatus.ACTIVE,
          SubscriptionStatus.PAST_DUE_GRACE,
          SubscriptionStatus.PAST_DUE_RESTRICTED);

  private final JdbcClient jdbcClient;
  private final SubscriptionRepository subscriptionRepository;
  private final BillingQueryRepository queryRepository;
  private final PriceScheduleSelector selector;
  private final BillingContextService context;
  private final BillingNoticeDispatcher noticeDispatcher;
  private final AdvisoryLock advisoryLock;

  public PriceChangeNoticeIssuer(
      JdbcClient jdbcClient,
      SubscriptionRepository subscriptionRepository,
      BillingQueryRepository queryRepository,
      PriceScheduleSelector selector,
      BillingContextService context,
      BillingNoticeDispatcher noticeDispatcher,
      AdvisoryLock advisoryLock) {
    this.jdbcClient = jdbcClient;
    this.subscriptionRepository = subscriptionRepository;
    this.queryRepository = queryRepository;
    this.selector = selector;
    this.context = context;
    this.noticeDispatcher = noticeDispatcher;
    this.advisoryLock = advisoryLock;
  }

  /**
   * 예약 상태의 새 표준 요금표 버전으로 사무소별 안내를 만든다. 안내 시각은 지금이고 효력 시각은 지금부터 30일 뒤 이후여야 한다. officeIds가 null이면 구독이
   * 있는 모든 사무소가 대상이다.
   */
  @Transactional
  public IssueResult issueForSchedule(long scheduleId, List<Long> officeIds, Instant now) {
    Instant notifiedAt = now.truncatedTo(ChronoUnit.MICROS);
    ScheduleState schedule =
        lockSchedule(scheduleId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!schedule.status().equals("SCHEDULED") || !schedule.audience().equals("STANDARD")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (schedule.effectiveAt().isBefore(notifiedAt.plus(NOTICE_PERIOD))) {
      throw new BusinessException(ErrorCode.PRICE_NOTICE_PERIOD_TOO_SHORT);
    }
    PriceScheduleRow to = queryRepository.findPriceById(scheduleId).orElseThrow();
    List<Long> targets =
        officeIds == null ? subscribedOffices() : officeIds.stream().distinct().toList();
    List<Long> created = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    for (Long officeId : targets) {
      Optional<Subscription> subscription = subscriptionRepository.findByOfficeId(officeId);
      if (subscription.isEmpty()) {
        skipped.add(new Skipped(officeId, "NO_PAYING_SUBSCRIPTION"));
        continue;
      }
      advisoryLock.lockForTransaction("billing:subscription:" + subscription.get().getId());
      Subscription locked =
          subscriptionRepository.findById(subscription.get().getId()).orElseThrow();
      Outcome outcome = issueOne(locked, to, schedule.effectiveAt(), notifiedAt, true);
      if (outcome.noticeId() != null) {
        created.add(outcome.noticeId());
      } else {
        skipped.add(new Skipped(officeId, outcome.skipReason()));
      }
    }
    return new IssueResult(scheduleId, created, skipped);
  }

  /** 한 구독에 안내 하나를 만든다. 새 가격은 효력 시각 이후 첫 청구기간부터 적용하고 응답 기한은 효력 시각이다. notify가 거짓이면 알림은 호출한 쪽이 남긴다. */
  Outcome issueOne(
      Subscription subscription,
      PriceScheduleRow to,
      Instant effectiveAt,
      Instant notifiedAt,
      boolean notify) {
    if (!PAYING.contains(subscription.getStatus()) || subscription.getCurrentPeriodEnd() == null) {
      return Outcome.skip("NO_PAYING_SUBSCRIPTION");
    }
    if (exists(subscription.getId(), to.id())) {
      return Outcome.skip("ALREADY_NOTIFIED");
    }
    long officeId = subscription.getOfficeId();
    Instant appliesFrom = firstPeriodStartFrom(subscription, effectiveAt);
    Optional<PriceSelection> atApply = selector.find(officeId, appliesFrom);
    if (atApply.isPresent() && atApply.get().startCustomer()) {
      return Outcome.skip("START_CUSTOMER_PRICE_LOCK");
    }
    Optional<PriceSelection> current = selector.find(officeId, notifiedAt);
    if (current.isEmpty() || current.get().schedule().id() == to.id()) {
      return Outcome.skip("NO_CHANGE");
    }
    return insert(
        subscription, current.get().schedule(), to, effectiveAt, appliesFrom, notifiedAt, notify);
  }

  /**
   * 승인된 시작 고객 합의를 취소했을 때의 안내다. 청구서가 있고 보장 기간이 30일 넘게 남았으며 표준 가격이 오를 때만 지금부터 30일 뒤를 효력으로 만든다. 안내가
   * 적용되기 전까지는 합의 가격을 계속 쓴다(PriceScheduleSelector).
   */
  @Transactional
  public Optional<Long> issueForRevokedAgreement(
      long officeId, long agreementScheduleId, Instant priceEndsAt, Instant now) {
    Instant notifiedAt = now.truncatedTo(ChronoUnit.MICROS);
    Instant effectiveAt = notifiedAt.plus(NOTICE_PERIOD);
    Optional<Subscription> found = subscriptionRepository.findByOfficeId(officeId);
    if (found.isEmpty()
        || !priceEndsAt.isAfter(effectiveAt)
        || queryRepository.countInvoices(found.get().getId()) == 0) {
      return Optional.empty();
    }
    advisoryLock.lockForTransaction("billing:subscription:" + found.get().getId());
    Subscription subscription = subscriptionRepository.findById(found.get().getId()).orElseThrow();
    if (!PAYING.contains(subscription.getStatus()) || subscription.getCurrentPeriodEnd() == null) {
      return Optional.empty();
    }
    Instant appliesFrom = firstPeriodStartFrom(subscription, effectiveAt);
    Optional<PriceScheduleRow> standard =
        queryRepository.findStandardPriceIncludingScheduledAt(appliesFrom);
    PriceScheduleRow from = queryRepository.findPriceById(agreementScheduleId).orElseThrow();
    if (standard.isEmpty()) {
      return Optional.empty();
    }
    int members = context.billableMembers(officeId);
    if (PriceCalculator.monthlyAmount(table(standard.get()), members)
        <= PriceCalculator.monthlyAmount(table(from), members)) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        issueFrom(subscription, from, standard.get(), effectiveAt, notifiedAt, true).noticeId());
  }

  /** 시작 고객 가격 보장 종료와 합의 취소처럼 출발 가격이 정해진 안내다. 금액이 같으면 만들지 않는다. */
  Outcome issueFrom(
      Subscription subscription,
      PriceScheduleRow from,
      PriceScheduleRow to,
      Instant effectiveAt,
      Instant notifiedAt,
      boolean notify) {
    if (!PAYING.contains(subscription.getStatus()) || subscription.getCurrentPeriodEnd() == null) {
      return Outcome.skip("NO_PAYING_SUBSCRIPTION");
    }
    if (from.id() == to.id()) {
      return Outcome.skip("NO_CHANGE");
    }
    if (exists(subscription.getId(), to.id())) {
      return Outcome.skip("ALREADY_NOTIFIED");
    }
    Instant appliesFrom = firstPeriodStartFrom(subscription, effectiveAt);
    return insert(subscription, from, to, effectiveAt, appliesFrom, notifiedAt, notify);
  }

  private Outcome insert(
      Subscription subscription,
      PriceScheduleRow from,
      PriceScheduleRow to,
      Instant effectiveAt,
      Instant appliesFrom,
      Instant notifiedAt,
      boolean notify) {
    long officeId = subscription.getOfficeId();
    int members = context.billableMembers(officeId);
    long currentAmount = PriceCalculator.monthlyAmount(table(from), members);
    long newAmount = PriceCalculator.monthlyAmount(table(to), members);
    if (currentAmount == newAmount) {
      return Outcome.skip("NO_CHANGE");
    }
    boolean increase = newAmount > currentAmount;
    if (increase && effectiveAt.isBefore(notifiedAt.plus(NOTICE_PERIOD))) {
      return Outcome.skip("NO_CHANGE");
    }
    long noticeId =
        jdbcClient
            .sql(
                "INSERT INTO pricing_change_notice (office_id, subscription_id,"
                    + " from_price_schedule_id, to_price_schedule_id, change_kind,"
                    + " member_count_snapshot, current_amount_incl_vat, new_amount_incl_vat,"
                    + " notified_at, effective_at, applies_from_period_start, response_due_at,"
                    + " decision) VALUES (:office, :subscription, :from, :to, :kind, :members,"
                    + " :current, :new, :notified, :effective, :applies, :due, :decision)"
                    + " RETURNING id")
            .param("office", officeId)
            .param("subscription", subscription.getId())
            .param("from", from.id())
            .param("to", to.id())
            .param("kind", increase ? "INCREASE" : "NON_INCREASE")
            .param("members", members)
            .param("current", currentAmount)
            .param("new", newAmount)
            .param("notified", Timestamp.from(notifiedAt))
            .param("effective", Timestamp.from(effectiveAt))
            .param("applies", Timestamp.from(appliesFrom))
            .param("due", Timestamp.from(effectiveAt))
            .param("decision", increase ? "PENDING" : "NOT_REQUIRED")
            .query(Long.class)
            .single();
    if (notify) {
      List<Long> notifications =
          noticeDispatcher.priceChange(
              officeId,
              increase,
              effectiveAt,
              currentAmount,
              newAmount,
              members,
              appliesFrom,
              effectiveAt);
      linkNotification(noticeId, notifications);
    }
    return new Outcome(noticeId, null, increase, currentAmount, newAmount, members);
  }

  void linkNotification(long noticeId, List<Long> notificationIds) {
    if (notificationIds.isEmpty()) {
      return;
    }
    jdbcClient
        .sql(
            "UPDATE pricing_change_notice SET notification_id = :notification WHERE id = :id"
                + " AND notification_id IS NULL")
        .param("notification", notificationIds.getFirst())
        .param("id", noticeId)
        .update();
  }

  /** 효력 시각 이후 처음 시작하는 청구기간의 시작이다. 갱신 배치가 같은 경계로 안내를 찾는다. */
  static Instant firstPeriodStartFrom(Subscription subscription, Instant effectiveAt) {
    Instant boundary = subscription.getCurrentPeriodEnd();
    int guard = 0;
    while (boundary.isBefore(effectiveAt) && guard++ < 1200) {
      boundary = BillingJobService.nextBoundary(subscription.getCycleAnchorAt(), boundary);
    }
    return boundary;
  }

  private boolean exists(long subscriptionId, long toScheduleId) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM pricing_change_notice WHERE subscription_id = :subscription"
                    + " AND to_price_schedule_id = :to")
            .param("subscription", subscriptionId)
            .param("to", toScheduleId)
            .query(Long.class)
            .single()
        > 0;
  }

  private List<Long> subscribedOffices() {
    return jdbcClient
        .sql("SELECT office_id FROM subscription ORDER BY office_id")
        .query(Long.class)
        .list();
  }

  private Optional<ScheduleState> lockSchedule(long scheduleId) {
    return jdbcClient
        .sql("SELECT status, audience, effective_at FROM price_schedule WHERE id = :id FOR UPDATE")
        .param("id", scheduleId)
        .query(
            (rs, n) ->
                new ScheduleState(
                    rs.getString("status"),
                    rs.getString("audience"),
                    rs.getTimestamp("effective_at").toInstant()))
        .optional();
  }

  private static PriceTable table(PriceScheduleRow row) {
    return new PriceTable(row.soloAmount(), row.teamFirstExtra(), row.teamAfterFourExtra());
  }

  private record ScheduleState(String status, String audience, Instant effectiveAt) {}

  public record Skipped(long officeId, String reason) {}

  public record IssueResult(long scheduleId, List<Long> createdNoticeIds, List<Skipped> skipped) {}

  public record Outcome(
      Long noticeId,
      String skipReason,
      boolean increase,
      long currentAmount,
      long newAmount,
      int members) {

    static Outcome skip(String reason) {
      return new Outcome(null, reason, false, 0, 0, 0);
    }
  }
}
