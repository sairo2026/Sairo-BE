package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitRequestNoticeRepository extends JpaRepository<VisitRequestNotice, Long> {

  List<VisitRequestNotice> findByRecipientMembershipIdOrderByCreatedAtDescIdDesc(
      Long recipientMembershipId, Pageable pageable);

  List<VisitRequestNotice> findByRecipientMembershipIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
      Long recipientMembershipId, Pageable pageable);

  long countByRecipientMembershipId(Long recipientMembershipId);

  long countByRecipientMembershipIdAndReadAtIsNull(Long recipientMembershipId);

  @Modifying
  @Query(
      "update VisitRequestNotice n set n.readAt = :at"
          + " where n.recipientMembershipId = :membershipId and n.readAt is null")
  int markAllRead(@Param("membershipId") Long membershipId, @Param("at") Instant at);

  Optional<VisitRequestNotice> findByIdAndRecipientMembershipId(
      Long id, Long recipientMembershipId);
}
