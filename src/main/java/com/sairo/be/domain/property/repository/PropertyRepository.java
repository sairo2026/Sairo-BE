package com.sairo.be.domain.property.repository;

import com.sairo.be.domain.property.entity.Property;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyRepository extends JpaRepository<Property, Long> {

  Optional<Property> findByIdAndOfficeId(Long id, Long officeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Property p where p.id = :id and p.officeId = :officeId")
  Optional<Property> findForUpdate(@Param("id") Long id, @Param("officeId") Long officeId);
}
