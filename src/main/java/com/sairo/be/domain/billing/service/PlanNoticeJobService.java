package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.BillingCustomer;
import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingCustomerRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.repository.SubscriptionCancellationRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.domain.billing.service.PriceCalculator.PriceTable;
import com.sairo.be.global.support.AdvisoryLock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 요금 안내 배치다. 가격 인상 응답 기한 14·7·1일 전 재안내, 시작 고객 가격 보장 종료 45일 전 안내, 자동 갱신 결제 7일 전 사전 안내를 대표 알림함에 남긴다.
 * 각 안내는 system_job_run 키로 한 번만 보낸다.
 */
@Profile("!migrate")
@Service
public class PlanNoticeJobService {

  static final List<Integer> REMINDER_DAYS = List.of(1, 7, 14);
  static final Duration GUARANTEE_NOTICE_BEFORE = Duration.ofDays(45);
  static final Duration PAYMENT_NOTICE_BEFORE = Duration.ofDays(7);

  private final JdbcClient jdbcClient;
  private final SubscriptionRepository subscriptionRepository;
  private final SubscriptionCancellationRepository cancellationRepository;
  private final BillingCustomerRepository customerRepository;
  private final BillingQueryRepository queryRepository;
  private final PriceScheduleSelector selector;
  private final PriceChangeNoticeIssuer issuer;
  private final BillingContextService context;
  private final BillingNoticeDispatcher noticeDispatcher;
  private final SystemJobRunService jobRunService;
  private final AdvisoryLock advisoryLock;
  private final TransactionTemplate transactionTemplate;

  public PlanNoticeJobService(
      JdbcClient jdbcClient,
      SubscriptionRepository subscriptionRepository,
      SubscriptionCancellationRepository cancellationRepository,
      BillingCustomerRepository customerRepository,
      BillingQueryRepository queryRepository,
      PriceScheduleSelector selector,
      PriceChangeNoticeIssuer issuer,
      BillingContextService context,
      BillingNoticeDispatcher noticeDispatcher,
      SystemJobRunService jobRunService,
      AdvisoryLock advisoryLock,
      TransactionTemplate transactionTemplate) {
    this.jdbcClient = jdbcClient;
    this.subscriptionRepository = subscriptionRepository;
    this.cancellationRepository = cancellationRepository;
    this.customerRepository = customerRepository;
    this.queryRepository = queryRepository;
    this.selector = selector;
    this.issuer = issuer;
    this.context = context;
    this.noticeDispatcher = noticeDispatcher;
    this.jobRunService = jobRunService;
    this.advisoryLock = advisoryLock;
    this.transactionTemplate = transactionTemplate;
  }

  public void runAll(Instant now) {
    runPriceChangeReminders(now);
    runGuaranteeEndingNotices(now);
    runPaymentUpcomingNotices(now);
  }

  /** 응답 대기 중인 인상 안내를 응답 기한 14·7·1일 전에 다시 보낸다. 지금 지난 가장 가까운 기준 하나만 보낸다. */
  public void runPriceChangeReminders(Instant now) {
    Instant until = now.plus(Duration.ofDays(REMINDER_DAYS.getLast()));
    for (ReminderRow row : pendingIncreases(now, until)) {
      Duration remaining = Duration.between(now, row.responseDueAt());
      Integer days =
          REMINDER_DAYS.stream()
              .filter(day -> remaining.compareTo(Duration.ofDays(day)) <= 0)
              .findFirst()
              .orElse(null);
      if (days == null
          || row.notifiedAt().isAfter(row.responseDueAt().minus(Duration.ofDays(days)))) {
        continue;
      }
      jobRunService.runOnce(
          "billing-price-change-reminder",
          row.officeId(),
          "price-change-reminder:" + row.id() + ":D" + days,
          row.responseDueAt().minus(Duration.ofDays(days)),
          () -> remind(row.id(), now));
    }
  }

  private void remind(long noticeId, Instant now) {
    transactionTemplate.executeWithoutResult(
        status -> {
          Optional<ReminderRow> row = notice(noticeId);
          if (row.isEmpty() || !row.get().pending() || !now.isBefore(row.get().responseDueAt())) {
            return;
          }
          ReminderRow notice = row.get();
          noticeDispatcher.priceChange(
              notice.officeId(),
              true,
              notice.effectiveAt(),
              notice.currentAmount(),
              notice.newAmount(),
              notice.members(),
              notice.appliesFrom(),
              notice.responseDueAt());
        });
  }

  /**
   * 승인된 시작 고객 합의의 가격 보장 종료 45일 전 안내다. 유료 구독이면 보장 종료 시각을 효력으로 하는 가격 변경 안내 행도 함께 만들어 갱신 배치가 인상 동의를
   * 검사하게 한다. 알림은 보장 종료 안내 하나만 남긴다.
   */
  public void runGuaranteeEndingNotices(Instant now) {
    for (GuaranteeRow row : guaranteesEndingBy(now, now.plus(GUARANTEE_NOTICE_BEFORE))) {
      jobRunService.runOnce(
          "billing-start-customer-guarantee-ending",
          row.officeId(),
          "start-customer-guarantee-ending:"
              + row.agreementId()
              + ":"
              + row.priceEndsAt().getEpochSecond(),
          row.priceEndsAt().minus(GUARANTEE_NOTICE_BEFORE),
          () -> guaranteeEnding(row, now));
    }
  }

  private void guaranteeEnding(GuaranteeRow row, Instant now) {
    transactionTemplate.executeWithoutResult(
        status -> {
          Optional<Subscription> found = subscriptionRepository.findByOfficeId(row.officeId());
          if (found.isEmpty() || found.get().getStatus() == SubscriptionStatus.ENDED) {
            return;
          }
          advisoryLock.lockForTransaction("billing:subscription:" + found.get().getId());
          Subscription subscription =
              subscriptionRepository.findById(found.get().getId()).orElseThrow();
          Optional<PriceScheduleRow> standard =
              queryRepository.findStandardPriceIncludingScheduledAt(row.priceEndsAt());
          if (standard.isEmpty()) {
            return;
          }
          int members = context.billableMembers(row.officeId());
          long standardAmount = PriceCalculator.monthlyAmount(table(standard.get()), members);
          List<Long> notifications =
              noticeDispatcher.guaranteeEnding(
                  row.officeId(), row.priceEndsAt(), standardAmount, members);
          PriceScheduleRow from =
              queryRepository.findPriceById(row.priceScheduleId()).orElseThrow();
          PriceChangeNoticeIssuer.Outcome outcome =
              issuer.issueFrom(
                  subscription,
                  from,
                  standard.get(),
                  row.priceEndsAt(),
                  now.truncatedTo(ChronoUnit.MICROS),
                  false);
          if (outcome.noticeId() != null) {
            issuer.linkNotification(outcome.noticeId(), notifications);
          }
        });
  }

  /** 자동 갱신 7일 전 결제 사전 안내다. 해지 예약이 있거나 인상 동의가 없어 끝날 회차에는 보내지 않는다. */
  public void runPaymentUpcomingNotices(Instant now) {
    for (Subscription subscription :
        subscriptionRepository
            .findByStatusAndCurrentPeriodEndGreaterThanAndCurrentPeriodEndLessThanEqual(
                SubscriptionStatus.ACTIVE, now, now.plus(PAYMENT_NOTICE_BEFORE))) {
      Instant chargeAt = subscription.getCurrentPeriodEnd();
      jobRunService.runOnce(
          "billing-payment-upcoming-notice",
          subscription.getOfficeId(),
          "payment-upcoming-notice:" + subscription.getId() + ":" + chargeAt.getEpochSecond(),
          chargeAt.minus(PAYMENT_NOTICE_BEFORE),
          () -> paymentUpcoming(subscription.getId(), chargeAt));
    }
  }

  private void paymentUpcoming(long subscriptionId, Instant chargeAt) {
    transactionTemplate.executeWithoutResult(
        status -> {
          advisoryLock.lockForTransaction("billing:subscription:" + subscriptionId);
          Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
          if (subscription.getStatus() != SubscriptionStatus.ACTIVE
              || !chargeAt.equals(subscription.getCurrentPeriodEnd())
              || cancellationRepository
                  .findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(subscriptionId)
                  .isPresent()
              || endsWithoutConsent(subscriptionId, chargeAt)) {
            return;
          }
          long officeId = subscription.getOfficeId();
          Optional<PriceScheduleSelector.PriceSelection> price = selector.find(officeId, chargeAt);
          if (price.isEmpty()) {
            return;
          }
          int members = context.billableMembers(officeId);
          long amount = PriceCalculator.monthlyAmount(price.get().table(), members);
          Optional<BillingCustomer> customer =
              customerRepository.findByOfficeId(officeId).filter(BillingCustomer::hasBillingKey);
          noticeDispatcher.paymentUpcoming(
              officeId,
              chargeAt,
              amount,
              members,
              customer.map(BillingCustomer::getCardIssuer).orElse(null),
              customer.map(BillingCustomer::getCardLast4).orElse(null));
        });
  }

  private boolean endsWithoutConsent(long subscriptionId, Instant periodStart) {
    return jdbcClient
            .sql(
                "SELECT count(*) FROM pricing_change_notice WHERE subscription_id = :subscription"
                    + " AND applies_from_period_start = :start AND change_kind = 'INCREASE'"
                    + " AND decision <> 'AGREED'")
            .param("subscription", subscriptionId)
            .param("start", Timestamp.from(periodStart))
            .query(Long.class)
            .single()
        > 0;
  }

  private List<ReminderRow> pendingIncreases(Instant now, Instant until) {
    return jdbcClient
        .sql(
            REMINDER_COLUMNS
                + " WHERE n.change_kind = 'INCREASE' AND n.decision = 'PENDING'"
                + " AND n.response_due_at > :now AND n.response_due_at <= :until ORDER BY n.id")
        .param("now", Timestamp.from(now))
        .param("until", Timestamp.from(until))
        .query(PlanNoticeJobService::mapReminder)
        .list();
  }

  private Optional<ReminderRow> notice(long id) {
    return jdbcClient
        .sql(REMINDER_COLUMNS + " WHERE n.id = :id FOR UPDATE")
        .param("id", id)
        .query(PlanNoticeJobService::mapReminder)
        .optional();
  }

  private List<GuaranteeRow> guaranteesEndingBy(Instant now, Instant until) {
    return jdbcClient
        .sql(
            "SELECT id, office_id, price_schedule_id, price_ends_at FROM"
                + " billing_start_customer_agreement WHERE status = 'APPROVED'"
                + " AND price_ends_at > :now AND price_ends_at <= :until ORDER BY id")
        .param("now", Timestamp.from(now))
        .param("until", Timestamp.from(until))
        .query(
            (rs, n) ->
                new GuaranteeRow(
                    rs.getLong("id"),
                    rs.getLong("office_id"),
                    rs.getLong("price_schedule_id"),
                    rs.getTimestamp("price_ends_at").toInstant()))
        .list();
  }

  private static final String REMINDER_COLUMNS =
      "SELECT n.id, n.office_id, n.decision, n.notified_at, n.effective_at,"
          + " n.applies_from_period_start, n.response_due_at, n.current_amount_incl_vat,"
          + " n.new_amount_incl_vat, n.member_count_snapshot FROM pricing_change_notice n";

  private static ReminderRow mapReminder(java.sql.ResultSet rs, int n)
      throws java.sql.SQLException {
    return new ReminderRow(
        rs.getLong("id"),
        rs.getLong("office_id"),
        "PENDING".equals(rs.getString("decision")),
        rs.getTimestamp("notified_at").toInstant(),
        rs.getTimestamp("effective_at").toInstant(),
        rs.getTimestamp("applies_from_period_start").toInstant(),
        rs.getTimestamp("response_due_at").toInstant(),
        rs.getLong("current_amount_incl_vat"),
        rs.getLong("new_amount_incl_vat"),
        rs.getInt("member_count_snapshot"));
  }

  private static PriceTable table(PriceScheduleRow row) {
    return new PriceTable(row.soloAmount(), row.teamFirstExtra(), row.teamAfterFourExtra());
  }

  record ReminderRow(
      long id,
      long officeId,
      boolean pending,
      Instant notifiedAt,
      Instant effectiveAt,
      Instant appliesFrom,
      Instant responseDueAt,
      long currentAmount,
      long newAmount,
      int members) {}

  record GuaranteeRow(long agreementId, long officeId, long priceScheduleId, Instant priceEndsAt) {}
}
