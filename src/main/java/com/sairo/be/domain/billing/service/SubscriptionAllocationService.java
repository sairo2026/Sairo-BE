package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionPlan;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.StartCustomerEventRow;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.TrialEventDetail;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.global.support.AdvisoryLock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class SubscriptionAllocationService {

  static final Duration DURATION_FREE_PERIOD = Duration.ofDays(60);

  private final SubscriptionRepository subscriptionRepository;
  private final BillingQueryRepository queryRepository;
  private final AdvisoryLock advisoryLock;

  public SubscriptionAllocationService(
      SubscriptionRepository subscriptionRepository,
      BillingQueryRepository queryRepository,
      AdvisoryLock advisoryLock) {
    this.subscriptionRepository = subscriptionRepository;
    this.queryRepository = queryRepository;
    this.advisoryLock = advisoryLock;
  }

  @Transactional
  public Subscription allocateOnVerification(VerificationFacts facts) {
    Long officeId = facts.officeId();
    advisoryLock.lockForTransaction("billing:office-subscription:" + officeId);
    Optional<FreeGrant> grant = pickGrant(facts);
    Optional<Subscription> existing = subscriptionRepository.findByOfficeId(officeId);
    if (existing.isEmpty()) {
      Subscription created =
          grant
              .map(
                  found ->
                      Subscription.startTrial(
                          officeId,
                          SubscriptionPlan.SOLO,
                          facts.verifiedAt(),
                          found.endsAt(),
                          found.eventId()))
              .orElseGet(
                  () ->
                      Subscription.startPrePayment(
                          officeId, SubscriptionPlan.SOLO, facts.verifiedAt()));
      return subscriptionRepository.saveAndFlush(created);
    }
    Subscription subscription = existing.get();
    advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());
    if (subscription.getStatus() != SubscriptionStatus.PRE_PAYMENT
        || queryRepository.countInvoices(subscription.getId()) > 0) {
      return subscription;
    }
    grant.ifPresent(
        found -> subscription.applyTrial(facts.verifiedAt(), found.endsAt(), found.eventId()));
    return subscriptionRepository.saveAndFlush(subscription);
  }

  @Transactional
  public Optional<Subscription> promoteStartCustomer(Long officeId) {
    advisoryLock.lockForTransaction("billing:office-subscription:" + officeId);
    Optional<Subscription> existing = subscriptionRepository.findByOfficeId(officeId);
    if (existing.isEmpty()) {
      return Optional.empty();
    }
    Subscription subscription = existing.get();
    advisoryLock.lockForTransaction("billing:subscription:" + subscription.getId());
    boolean eligibleStatus =
        subscription.getStatus() == SubscriptionStatus.TRIAL
            || subscription.getStatus() == SubscriptionStatus.PRE_PAYMENT;
    Optional<StartCustomerEventRow> startEvent =
        queryRepository.findStartCustomerEventFor(officeId);
    if (!eligibleStatus
        || startEvent.isEmpty()
        || queryRepository.countInvoices(subscription.getId()) > 0) {
      return Optional.of(subscription);
    }
    TrialEventDetail event = startEvent.get().event();
    Instant basis =
        subscription.getTrialStartedAt() != null
            ? subscription.getTrialStartedAt()
            : startEvent.get().approvedAt();
    Optional<Instant> freeEnd = freeEnd(event, basis);
    if (freeEnd.isEmpty()) {
      return Optional.of(subscription);
    }
    if (subscription.getStatus() == SubscriptionStatus.PRE_PAYMENT) {
      subscription.applyTrial(basis, freeEnd.get(), event.id());
    } else if (freeEnd.get().isAfter(subscription.getTrialEndsAt())) {
      subscription.extendTrial(freeEnd.get(), event.id());
    }
    return Optional.of(subscriptionRepository.saveAndFlush(subscription));
  }

  private Optional<FreeGrant> pickGrant(VerificationFacts facts) {
    Optional<FreeGrant> startCustomer =
        queryRepository
            .findStartCustomerEventFor(facts.officeId())
            .flatMap(
                row ->
                    freeEnd(row.event(), facts.verifiedAt())
                        .map(end -> new FreeGrant(row.event().id(), end)));
    if (startCustomer.isPresent()) {
      return startCustomer;
    }
    return queryRepository
        .findActiveTrialEventAt("GENERAL", facts.verifiedAt())
        .filter(event -> withinConditions(event, facts))
        .flatMap(
            event -> freeEnd(event, facts.verifiedAt()).map(end -> new FreeGrant(event.id(), end)));
  }

  private boolean withinConditions(TrialEventDetail event, VerificationFacts facts) {
    if (event.officeCreatedDuring() && !inWindow(event, facts.officeCreatedAt())) {
      return false;
    }
    return !event.firstApplicationDuring() || inWindow(event, facts.firstApplicationAt());
  }

  private static boolean inWindow(TrialEventDetail event, Instant at) {
    return at != null && !at.isBefore(event.startsAt()) && at.isBefore(event.endsAt());
  }

  private static Optional<Instant> freeEnd(TrialEventDetail event, Instant basis) {
    if ("FIXED_END".equals(event.freeEndMode())) {
      Instant fixed = event.fixedFreeEndsAt();
      return fixed != null && fixed.isAfter(basis) ? Optional.of(fixed) : Optional.empty();
    }
    return Optional.of(basis.plus(DURATION_FREE_PERIOD));
  }

  public record VerificationFacts(
      Long officeId, Instant verifiedAt, Instant officeCreatedAt, Instant firstApplicationAt) {}

  private record FreeGrant(long eventId, Instant endsAt) {}
}
