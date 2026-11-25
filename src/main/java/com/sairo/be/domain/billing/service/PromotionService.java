package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.PromotionRepository;
import com.sairo.be.domain.billing.repository.PromotionRepository.Campaign;
import com.sairo.be.domain.billing.repository.PromotionRepository.Grant;
import com.sairo.be.domain.billing.repository.SubscriptionCancellationRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PromotionOperatorPort;
import com.sairo.be.global.security.OperatorStepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Free service periods have their own ledger. No method in this class calls the payment gateway or
 * writes a PAID billing invoice.
 */
@Profile("!migrate")
@Service
public class PromotionService {
  private final PromotionRepository promotions;
  private final SubscriptionRepository subscriptions;
  private final BillingInvoiceRepository invoices;
  private final SubscriptionCancellationRepository cancellations;
  private final BillingQueryRepository queries;
  private final PriceScheduleSelector prices;
  private final BillingContextService context;
  private final AdvisoryLock locks;
  private final PromotionOperatorPort operators;
  private final OperatorStepUpState stepUp;
  private final TransactionTemplate transaction;
  private final SystemJobRunService jobRuns;
  private final Clock clock;

  public PromotionService(
      PromotionRepository promotions,
      SubscriptionRepository subscriptions,
      BillingInvoiceRepository invoices,
      SubscriptionCancellationRepository cancellations,
      BillingQueryRepository queries,
      PriceScheduleSelector prices,
      BillingContextService context,
      AdvisoryLock locks,
      PromotionOperatorPort operators,
      OperatorStepUpState stepUp,
      TransactionTemplate transaction,
      SystemJobRunService jobRuns,
      Clock clock) {
    this.promotions = promotions;
    this.subscriptions = subscriptions;
    this.invoices = invoices;
    this.cancellations = cancellations;
    this.queries = queries;
    this.prices = prices;
    this.context = context;
    this.locks = locks;
    this.operators = operators;
    this.stepUp = stepUp;
    this.transaction = transaction;
    this.jobRuns = jobRuns;
    this.clock = clock;
  }

  @Transactional
  public long createCampaign(
      long operatorId,
      String name,
      String code,
      Instant startsAt,
      Instant endsAt,
      int freeMonths,
      Integer maxOffices) {
    if (name == null
        || name.isBlank()
        || name.length() > 100
        || code == null
        || !code.matches("[A-Z0-9_-]{4,40}")
        || startsAt == null
        || endsAt == null
        || !endsAt.isAfter(startsAt)
        || freeMonths < 1
        || freeMonths > 120
        || maxOffices != null && maxOffices < 1) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    requireBillingOperator(operatorId);
    long id =
        promotions.createCampaign(
            name.strip(), code, startsAt, endsAt, freeMonths, maxOffices, operatorId);
    operators.record(
        operatorId,
        "PROMOTION_CAMPAIGN_CREATED",
        "PROMOTION_CAMPAIGN",
        id,
        "코드 " + code + ", 무료 개월 " + freeMonths,
        now());
    return id;
  }

  @Transactional
  public void activateCampaign(long operatorId, long campaignId, HttpSession session) {
    stepUp.require(session);
    Campaign campaign =
        promotions
            .lockCampaign(campaignId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    if (!"DRAFT".equals(campaign.status())
        || campaign.creatorId() == operatorId
        || !operators.canManageBilling(operatorId)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    promotions.activateCampaign(campaignId, operatorId);
    operators.record(
        operatorId, "PROMOTION_CAMPAIGN_ACTIVATED", "PROMOTION_CAMPAIGN", campaignId, "승인", now());
  }

  @Transactional
  public void endCampaign(long operatorId, long campaignId, HttpSession session) {
    stepUp.require(session);
    requireBillingOperator(operatorId);
    Campaign campaign =
        promotions
            .lockCampaign(campaignId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    if (!"ACTIVE".equals(campaign.status())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    // Closing enrollment does not revoke periods already granted to offices.
    promotions.endCampaign(campaignId);
    operators.record(
        operatorId,
        "PROMOTION_CAMPAIGN_ENDED",
        "PROMOTION_CAMPAIGN",
        campaignId,
        "신규 지급 종료",
        now());
  }

  /**
   * A grant starts only from PRE_PAYMENT; it cannot silently discount an existing paid contract.
   */
  @Transactional
  public long grant(long operatorId, long campaignId, long officeId, HttpSession session) {
    stepUp.require(session);
    requireBillingOperator(operatorId);
    Instant start = now();
    Campaign campaign =
        promotions
            .lockCampaign(campaignId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    if (!"ACTIVE".equals(campaign.status())
        || start.isBefore(campaign.startsAt())
        || !start.isBefore(campaign.endsAt())
        || campaign.maxOffices() != null
            && promotions.grantsCount(campaignId) >= campaign.maxOffices()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    locks.lockForTransaction("billing:office-subscription:" + officeId);
    Subscription subscription =
        subscriptions
            .findByOfficeId(officeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    locks.lockForTransaction("billing:subscription:" + subscription.getId());
    if (subscription.getStatus() != SubscriptionStatus.PRE_PAYMENT
        || queries.countInvoices(subscription.getId()) > 0
        || promotions.hasLiveGrant(officeId)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant freeEnd = BillingPeriods.boundary(start, campaign.freeMonths());
    Instant firstPeriodEnd = BillingPeriods.boundary(start, 1);
    var price = prices.select(officeId, start);
    int members = context.billableMembers(officeId);
    long listAmount = PriceCalculator.monthlyAmount(price.table(), members);
    if (listAmount <= 0) {
      throw new BusinessException(ErrorCode.BILLING_AMOUNT_UNSUPPORTED);
    }
    subscription.applyPromotionTrial(start, freeEnd, campaignId);
    subscriptions.saveAndFlush(subscription);
    long grantId =
        promotions.createGrant(
            campaignId, officeId, subscription.getId(), start, freeEnd, firstPeriodEnd, operatorId);
    Grant grant = promotions.lockGrant(grantId).orElseThrow();
    promotions.createPeriod(grant, start, firstPeriodEnd, listAmount, price.version(), members);
    operators.record(
        operatorId,
        "PROMOTION_GRANTED",
        "PROMOTION_GRANT",
        grantId,
        "사무소 " + officeId + ", 캠페인 " + campaignId,
        start);
    return grantId;
  }

  @Transactional
  public void revoke(long operatorId, long grantId, HttpSession session) {
    stepUp.require(session);
    requireBillingOperator(operatorId);
    Grant observed =
        promotions
            .grant(grantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    locks.lockForTransaction("billing:subscription:" + observed.subscriptionId());
    Grant grant = promotions.lockGrant(grantId).orElseThrow();
    if (!"ACTIVE".equals(grant.status()) && !"RECONCILIATION_REQUIRED".equals(grant.status())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Subscription subscription = subscriptions.findById(grant.subscriptionId()).orElseThrow();
    if (subscription.getStatus() != SubscriptionStatus.TRIAL) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant at = now();
    subscription.enterPrePayment(at);
    subscriptions.saveAndFlush(subscription);
    promotions.finishGrant(grantId, "REVOKED", at);
    operators.record(
        operatorId,
        "PROMOTION_REVOKED",
        "PROMOTION_GRANT",
        grantId,
        "사무소 " + grant.officeId() + ", 즉시 무료 종료",
        at);
  }

  /** Records one missed free period after an operator verifies the historical member count. */
  @Transactional
  public void reconcilePeriod(
      long operatorId, long grantId, int historicalMembers, String reason, HttpSession session) {
    stepUp.require(session);
    requireBillingOperator(operatorId);
    if (historicalMembers < 1
        || reason == null
        || reason.strip().length() < 10
        || reason.length() > 500) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Grant observed =
        promotions
            .grant(grantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TRANSITION));
    locks.lockForTransaction("billing:subscription:" + observed.subscriptionId());
    Grant grant = promotions.lockGrant(grantId).orElseThrow();
    if (!"RECONCILIATION_REQUIRED".equals(grant.status())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Subscription subscription = subscriptions.findById(grant.subscriptionId()).orElseThrow();
    Instant start = grant.nextPeriodStart();
    Instant at = now();
    var cancellation =
        cancellations.findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(
            subscription.getId());
    if (cancellation.isPresent() && !cancellation.get().getEffectiveAt().isAfter(at)) {
      if (subscription.getStatus() != SubscriptionStatus.ENDED) {
        subscription.end(cancellation.get().getEffectiveAt());
        subscriptions.saveAndFlush(subscription);
      }
      promotions.finishGrant(grantId, "CANCELLED", at);
      operators.record(
          operatorId,
          "PROMOTION_RECONCILIATION_CANCELLED",
          "PROMOTION_GRANT",
          grantId,
          "office=" + grant.officeId() + ", reason=pending cancellation",
          at);
      return;
    }
    if (subscription.getStatus() != SubscriptionStatus.TRIAL
        && !(subscription.getStatus() == SubscriptionStatus.PRE_PAYMENT
            && !at.isBefore(grant.freeEndsAt()))) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (!start.isBefore(grant.freeEndsAt())
        || start.isAfter(at)
        || promotions.periodExists(grant.subscriptionId(), start)
        || invoices
            .findBySubscriptionIdAndBillingPeriodStart(grant.subscriptionId(), start)
            .isPresent()) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant next = BillingJobService.nextBoundary(grant.anchor(), start);
    Instant end = next.isAfter(grant.freeEndsAt()) ? grant.freeEndsAt() : next;
    var price = prices.select(grant.officeId(), start);
    long listAmount = PriceCalculator.monthlyAmount(price.table(), historicalMembers);
    if (listAmount <= 0) {
      throw new BusinessException(ErrorCode.BILLING_AMOUNT_UNSUPPORTED);
    }
    long periodId =
        promotions.createPeriod(grant, start, end, listAmount, price.version(), historicalMembers);
    promotions.markReconciledPeriod(periodId, operatorId, reason.strip());
    promotions.advanceGrant(grantId, end);
    if (!end.isBefore(grant.freeEndsAt()) && !at.isBefore(grant.freeEndsAt())) {
      subscription.enterPrePayment(at);
      subscriptions.saveAndFlush(subscription);
      promotions.finishGrant(grantId, "CONVERSION_PENDING", at);
    } else if (!end.plus(1, ChronoUnit.DAYS).isBefore(at)) {
      promotions.changeGrantStatus(grantId, "ACTIVE");
    }
    operators.record(
        operatorId,
        "PROMOTION_PERIOD_RECONCILED",
        "PROMOTION_PERIOD",
        periodId,
        "office="
            + grant.officeId()
            + ", members="
            + historicalMembers
            + ", reason="
            + reason.strip(),
        at);
  }

  /** Generates only elapsed monthly periods and moves expired grants to a non-charging state. */
  public void runDue(Instant now) {
    jobRuns.reclaimStale(
        List.of("promotion-free-renewal", "promotion-free-cancellation"),
        now.minus(BillingJobService.STALE_JOB_AFTER));
    for (long id : promotions.grantsWithEffectiveCancellation(now)) {
      promotions
          .grant(id)
          .ifPresent(
              grant ->
                  jobRuns.runOnce(
                      "promotion-free-cancellation",
                      grant.officeId(),
                      "promotion-free-cancellation:"
                          + id
                          + ":"
                          + grant.nextPeriodStart().getEpochSecond(),
                      now,
                      () ->
                          transaction.executeWithoutResult(
                              ignored -> closeEffectiveCancellation(id, now))));
    }
    for (long id : promotions.dueGrants(now)) {
      promotions
          .grant(id)
          .ifPresent(
              grant ->
                  jobRuns.runOnce(
                      "promotion-free-renewal",
                      grant.officeId(),
                      "promotion-free-renewal:"
                          + id
                          + ":"
                          + grant.nextPeriodStart().getEpochSecond(),
                      grant.nextPeriodStart(),
                      () -> transaction.executeWithoutResult(ignored -> processDue(id, now))));
    }
  }

  private void closeEffectiveCancellation(long grantId, Instant at) {
    Grant observed = promotions.grant(grantId).orElseThrow();
    locks.lockForTransaction("billing:subscription:" + observed.subscriptionId());
    Grant grant = promotions.lockGrant(grantId).orElseThrow();
    if (!List.of("ACTIVE", "SUSPENDED", "RECONCILIATION_REQUIRED").contains(grant.status())) {
      return;
    }
    var cancellation =
        cancellations.findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(
            grant.subscriptionId());
    if (cancellation.isEmpty() || cancellation.get().getEffectiveAt().isAfter(at)) {
      return;
    }
    Subscription subscription = subscriptions.findById(grant.subscriptionId()).orElseThrow();
    if (subscription.getStatus() != SubscriptionStatus.ENDED) {
      subscription.end(cancellation.get().getEffectiveAt());
      subscriptions.saveAndFlush(subscription);
    }
    promotions.finishGrant(grantId, "CANCELLED", at);
  }

  /** Called inside the transaction that ended a subscription. */
  public void onSubscriptionEnded(long subscriptionId, String reason, Instant at) {
    promotions
        .liveGrantForSubscription(subscriptionId)
        .ifPresent(grant -> promotions.finishGrant(grant.id(), reason, at));
  }

  private void processDue(long id, Instant now) {
    Grant observed = promotions.grant(id).orElseThrow();
    locks.lockForTransaction("billing:subscription:" + observed.subscriptionId());
    Grant grant = promotions.lockGrant(id).orElseThrow();
    if (!"ACTIVE".equals(grant.status())) {
      return;
    }
    Subscription subscription = subscriptions.findById(grant.subscriptionId()).orElseThrow();
    if (subscription.getStatus() == SubscriptionStatus.ENDED) {
      promotions.finishGrant(id, "ENDED", now);
      return;
    }
    if (subscription.getStatus() != SubscriptionStatus.TRIAL) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    var cancellation =
        cancellations.findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(
            subscription.getId());
    if (cancellation.isPresent() && !cancellation.get().getEffectiveAt().isAfter(now)) {
      subscription.end(cancellation.get().getEffectiveAt());
      subscriptions.saveAndFlush(subscription);
      promotions.finishGrant(id, "CANCELLED", now);
      return;
    }
    for (int catchup = 0; catchup < 120 && !grant.nextPeriodStart().isAfter(now); catchup++) {
      Instant start = grant.nextPeriodStart();
      if (!start.isBefore(grant.freeEndsAt())) {
        subscription.enterPrePayment(now);
        subscriptions.saveAndFlush(subscription);
        promotions.finishGrant(id, "CONVERSION_PENDING", now);
        return;
      }
      // Member counts are not historical snapshots. A long outage requires an operator
      // reconciliation instead of silently pricing past periods with today's membership.
      if (start.plus(1, ChronoUnit.DAYS).isBefore(now)) {
        promotions.changeGrantStatus(id, "RECONCILIATION_REQUIRED");
        return;
      }
      if (invoices
          .findBySubscriptionIdAndBillingPeriodStart(subscription.getId(), start)
          .isPresent()) {
        throw new BusinessException(ErrorCode.INVALID_TRANSITION);
      }
      Instant next = BillingJobService.nextBoundary(grant.anchor(), start);
      Instant end = next.isAfter(grant.freeEndsAt()) ? grant.freeEndsAt() : next;
      if (!promotions.periodExists(subscription.getId(), start)) {
        var price = prices.select(grant.officeId(), start);
        int members = context.billableMembers(grant.officeId());
        long listAmount = PriceCalculator.monthlyAmount(price.table(), members);
        promotions.createPeriod(grant, start, end, listAmount, price.version(), members);
      }
      promotions.advanceGrant(id, end);
      grant = promotions.lockGrant(id).orElseThrow();
    }
  }

  public boolean preventsAutomaticCharge(long subscriptionId) {
    return promotions.hasGrantForSubscription(subscriptionId);
  }

  /** Authentication suspension pauses renewal without forfeiting the original free end date. */
  public void onAuthSuspended(long subscriptionId) {
    promotions
        .liveGrantForSubscription(subscriptionId)
        .ifPresent(
            grant -> {
              if ("ACTIVE".equals(grant.status())) {
                promotions.changeGrantStatus(grant.id(), "SUSPENDED");
              }
            });
  }

  /** Caller holds the subscription advisory lock and transaction. */
  public boolean onAuthResumed(Subscription subscription, Instant at) {
    Optional<Grant> found = promotions.liveGrantForSubscription(subscription.getId());
    if (found.isEmpty()) {
      return false;
    }
    Grant grant = found.get();
    if ("RECONCILIATION_REQUIRED".equals(grant.status())) {
      if (at.isBefore(grant.freeEndsAt())) {
        subscription.resumeTrial(grant.freeEndsAt());
      } else {
        subscription.resumeToPrePayment(at);
      }
      return true;
    }
    if (!"SUSPENDED".equals(grant.status())) {
      return false;
    }
    if (at.isBefore(grant.freeEndsAt())) {
      subscription.resumeTrial(grant.freeEndsAt());
      promotions.changeGrantStatus(grant.id(), "ACTIVE");
    } else {
      subscription.resumeToPrePayment(at);
      promotions.finishGrant(grant.id(), "CONVERSION_PENDING", at);
    }
    return true;
  }

  /** The original entitlement ends when the authentication recovery window expires. */
  public void onAuthExpired(long subscriptionId, Instant at) {
    promotions
        .liveGrantForSubscription(subscriptionId)
        .ifPresent(grant -> promotions.finishGrant(grant.id(), "ENDED", at));
  }

  @Transactional(readOnly = true)
  public List<PeriodView> periodsForOffice(long officeId) {
    return promotions.periods(officeId).stream()
        .map(
            p ->
                new PeriodView(
                    p.id(),
                    p.periodStart(),
                    p.periodEnd(),
                    p.listAmount(),
                    p.discountAmount(),
                    p.chargedAmount(),
                    p.priceVersion(),
                    p.createdAt(),
                    p.campaignName(),
                    p.campaignCode(),
                    p.officeName(),
                    p.plan(),
                    p.freeEndsAt()))
        .toList();
  }

  @Transactional(readOnly = true)
  public Optional<CurrentPromotion> currentForOffice(long officeId) {
    return promotions
        .currentOrPendingGrantForOffice(officeId)
        .map(
            grant -> {
              Campaign campaign = promotions.campaign(grant.campaignId()).orElseThrow();
              Instant priceAt = "ACTIVE".equals(grant.status()) ? grant.freeEndsAt() : now();
              Long potentialPaidAmount =
                  prices
                      .find(officeId, priceAt)
                      .map(
                          price ->
                              PriceCalculator.monthlyAmount(
                                  price.table(), context.billableMembers(officeId)))
                      .orElse(null);
              return new CurrentPromotion(
                  campaign.name(),
                  campaign.code(),
                  grant.startsAt(),
                  grant.freeEndsAt(),
                  "ACTIVE".equals(grant.status())
                          && grant.nextPeriodStart().isBefore(grant.freeEndsAt())
                      ? grant.nextPeriodStart()
                      : null,
                  "RECONCILIATION_REQUIRED".equals(grant.status())
                      ? "RECONCILIATION_REQUIRED"
                      : "ACTIVE".equals(grant.status()) || "SUSPENDED".equals(grant.status())
                          ? "CONFIRMATION_REQUIRED"
                          : "CONVERSION_PENDING",
                  potentialPaidAmount);
            });
  }

  @Transactional(readOnly = true)
  public List<CampaignView> campaigns() {
    return promotions.campaigns().stream()
        .map(
            c ->
                new CampaignView(
                    c.id(),
                    c.name(),
                    c.code(),
                    c.startsAt(),
                    c.endsAt(),
                    c.freeMonths(),
                    c.status(),
                    c.maxOffices(),
                    c.creatorId()))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<GrantView> grants() {
    return promotions.grants().stream()
        .map(
            g ->
                new GrantView(
                    g.id(),
                    g.campaignId(),
                    g.officeId(),
                    g.subscriptionId(),
                    g.status(),
                    g.startsAt(),
                    g.freeEndsAt(),
                    g.anchor(),
                    g.nextPeriodStart()))
        .toList();
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private void requireBillingOperator(long operatorId) {
    if (!operators.canManageBilling(operatorId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  public record CurrentPromotion(
      String campaignName,
      String campaignCode,
      Instant startsAt,
      Instant freeEndsAt,
      Instant nextFreeRenewalAt,
      String conversionStatus,
      Long potentialPaidAmount) {}

  public record PeriodView(
      long id,
      Instant periodStart,
      Instant periodEnd,
      long listAmount,
      long discountAmount,
      long chargedAmount,
      String priceVersion,
      Instant createdAt,
      String campaignName,
      String campaignCode,
      String officeName,
      String plan,
      Instant freeEndsAt) {}

  public record CampaignView(
      long id,
      String name,
      String code,
      Instant startsAt,
      Instant endsAt,
      Integer freeMonths,
      String status,
      Integer maxOffices,
      long creatorId) {}

  public record GrantView(
      long id,
      long campaignId,
      long officeId,
      long subscriptionId,
      String status,
      Instant startsAt,
      Instant freeEndsAt,
      Instant anchor,
      Instant nextPeriodStart) {}
}
