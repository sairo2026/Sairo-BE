package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.PropertyAccessApproval;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PropertyAccessApprovalRepository
    extends JpaRepository<PropertyAccessApproval, Long> {

  Optional<PropertyAccessApproval> findByIdAndPropertyId(Long id, Long propertyId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from PropertyAccessApproval a where a.id = :id and a.propertyId = :propertyId")
  Optional<PropertyAccessApproval> findForUpdate(
      @Param("id") Long id, @Param("propertyId") Long propertyId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from PropertyAccessApproval a where a.id = :id")
  Optional<PropertyAccessApproval> findForUpdateById(@Param("id") Long id);

  Page<PropertyAccessApproval> findByPropertyId(Long propertyId, Pageable pageable);

  @Query(
      "select count(a) > 0 from PropertyAccessApproval a where a.propertyId = :propertyId"
          + " and a.approvalRole = com.sairo.be.domain.visit.entity.ApprovalRole.PRIMARY"
          + " and a.revokedAt is null and a.validFrom < :until and a.validUntil > :from")
  boolean existsActivePrimaryOverlap(
      @Param("propertyId") Long propertyId,
      @Param("from") Instant from,
      @Param("until") Instant until);

  @Query(
      "select a from PropertyAccessApproval a where a.propertyId = :propertyId"
          + " and a.approvalRole = com.sairo.be.domain.visit.entity.ApprovalRole.PRIMARY"
          + " and a.revokedAt is null and a.validFrom <= :now and a.validUntil > :now"
          + " order by a.id desc")
  List<PropertyAccessApproval> findUsablePrimaries(
      @Param("propertyId") Long propertyId, @Param("now") Instant now);
}
