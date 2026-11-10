package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitApproverPropertyLink;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitApproverPropertyLinkRepository
    extends JpaRepository<VisitApproverPropertyLink, Long> {

  interface LinkHead {

    Long getId();

    Long getApprovalId();

    Instant getExpiresAt();

    Instant getRevokedAt();
  }

  Optional<LinkHead> findHeadByTokenHash(String tokenHash);

  Optional<VisitApproverPropertyLink> findFirstByApprovalIdAndRevokedAtIsNull(Long approvalId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from VisitApproverPropertyLink l where l.id = :id")
  Optional<VisitApproverPropertyLink> findForUpdate(@Param("id") Long id);
}
