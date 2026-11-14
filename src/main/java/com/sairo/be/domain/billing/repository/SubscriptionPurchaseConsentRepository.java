package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.SubscriptionPurchaseConsent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPurchaseConsentRepository
    extends JpaRepository<SubscriptionPurchaseConsent, Long> {

  Optional<SubscriptionPurchaseConsent> findFirstBySubscriptionIdOrderByIdDesc(Long subscriptionId);
}
