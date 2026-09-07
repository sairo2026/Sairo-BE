package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.Property;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<Property, Long> {

  List<Property> findByOfficeIdOrderByIdAsc(Long officeId);

  List<Property> findByOfficeIdAndAddress(Long officeId, String address);

  Optional<Property> findByIdAndOfficeId(Long id, Long officeId);
}
