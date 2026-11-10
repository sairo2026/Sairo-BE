package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.PropertyPhoto;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyPhotoRepository extends JpaRepository<PropertyPhoto, Long> {

  List<PropertyPhoto> findByPropertyIdOrderBySortOrderAscIdAsc(Long propertyId);

  Optional<PropertyPhoto> findByIdAndPropertyId(Long id, Long propertyId);

  long countByPropertyId(Long propertyId);

  @Query(
      "select coalesce(max(p.sortOrder), 0) from PropertyPhoto p where p.propertyId = :propertyId")
  int findMaxSortOrder(@Param("propertyId") Long propertyId);
}
