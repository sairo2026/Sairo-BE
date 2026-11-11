package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.VisitRequestNotice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VisitRequestNoticeRepository extends JpaRepository<VisitRequestNotice, Long> {

  List<VisitRequestNotice> findByRecipientMembershipIdOrderByCreatedAtDescIdDesc(
      Long recipientMembershipId, Pageable pageable);

  List<VisitRequestNotice> findByRecipientMembershipIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(
      Long recipientMembershipId, Pageable pageable);

  Optional<VisitRequestNotice> findByIdAndRecipientMembershipId(
      Long id, Long recipientMembershipId);
}
