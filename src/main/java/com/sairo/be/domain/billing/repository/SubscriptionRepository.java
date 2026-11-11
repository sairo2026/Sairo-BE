package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.Subscription;
import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

  Optional<Subscription> findByOfficeId(Long officeId);

  List<Subscription> findByStatusAndFirstBillingScheduledAtLessThanEqual(
      SubscriptionStatus status, Instant at);

  List<Subscription> findByStatusAndCurrentPeriodEndLessThanEqual(
      SubscriptionStatus status, Instant at);

  List<Subscription> findByStatusIn(Collection<SubscriptionStatus> statuses);

  List<Subscription> findByStatusAndPrePaymentSinceLessThanEqual(
      SubscriptionStatus status, Instant at);

  List<Subscription> findByStatusAndTrialEndsAtGreaterThanAndTrialEndsAtLessThanEqual(
      SubscriptionStatus status, Instant after, Instant until);
}
