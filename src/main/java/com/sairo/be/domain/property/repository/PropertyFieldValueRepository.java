package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.PropertyFieldValue;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyFieldValueRepository extends JpaRepository<PropertyFieldValue, Long> {

  Optional<PropertyFieldValue> findByPropertyIdAndFieldDefinitionId(
      Long propertyId, Long fieldDefinitionId);

  List<PropertyFieldValue> findByPropertyIdAndArchived(Long propertyId, boolean archived);
}
