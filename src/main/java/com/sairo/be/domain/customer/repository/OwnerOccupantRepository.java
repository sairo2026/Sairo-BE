package com.sairo.be.domain.customer.repository;

import com.sairo.be.domain.customer.entity.PropertyOwnerOccupant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OwnerOccupantRepository extends JpaRepository<PropertyOwnerOccupant, Long> {

  Optional<PropertyOwnerOccupant> findByIdAndOfficeId(Long id, Long officeId);
}
