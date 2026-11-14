package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitCustomerLink;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitCustomerLinkRepository extends JpaRepository<VisitCustomerLink, Long> {

  interface LinkHead {

    Long getId();

    Long getCoordinationId();

    Instant getExpiresAt();

    Instant getRevokedAt();
  }

  Optional<LinkHead> findHeadByTokenHash(String tokenHash);

  Optional<VisitCustomerLink> findByTokenHash(String tokenHash);

  Optional<VisitCustomerLink> findFirstByCoordinationIdAndRevokedAtIsNull(Long coordinationId);

  Optional<VisitCustomerLink> findFirstByCoordinationIdOrderByIdDesc(Long coordinationId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from VisitCustomerLink l where l.id = :id")
  Optional<VisitCustomerLink> findForUpdate(@Param("id") Long id);
}
