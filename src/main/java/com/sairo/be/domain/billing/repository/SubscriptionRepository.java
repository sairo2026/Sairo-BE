package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.Subscription;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

  Optional<Subscription> findByOfficeId(Long officeId);
}
