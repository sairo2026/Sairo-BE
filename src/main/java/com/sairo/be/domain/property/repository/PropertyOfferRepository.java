package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.DealType;
import com.sairo.be.domain.property.entity.OfferStatus;
import com.sairo.be.domain.property.entity.PropertyOffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyOfferRepository extends JpaRepository<PropertyOffer, Long> {

  List<PropertyOffer> findByPropertyIdOrderById(Long propertyId);

  Optional<PropertyOffer> findByIdAndPropertyId(Long id, Long propertyId);

  boolean existsByPropertyIdAndDealTypeAndStatusInAndIdNot(
      Long propertyId, DealType dealType, Collection<OfferStatus> statuses, Long excludeId);
}
