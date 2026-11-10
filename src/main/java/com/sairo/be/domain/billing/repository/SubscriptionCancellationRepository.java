package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.SubscriptionCancellation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionCancellationRepository
    extends JpaRepository<SubscriptionCancellation, Long> {

  Optional<SubscriptionCancellation> findFirstBySubscriptionIdAndRevokedAtIsNullOrderByIdDesc(
      Long subscriptionId);
}
