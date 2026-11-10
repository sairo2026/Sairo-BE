package com.sairo.be.domain.billing.repository;

import com.sairo.be.domain.billing.entity.PriceChangeNotice;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceChangeNoticeRepository extends JpaRepository<PriceChangeNotice, Long> {

  Optional<PriceChangeNotice> findByIdAndOfficeId(Long id, Long officeId);

  Page<PriceChangeNotice> findByOfficeId(Long officeId, Pageable pageable);

  Optional<PriceChangeNotice> findBySubscriptionIdAndAppliesFromPeriodStart(
      Long subscriptionId, Instant appliesFromPeriodStart);
}
