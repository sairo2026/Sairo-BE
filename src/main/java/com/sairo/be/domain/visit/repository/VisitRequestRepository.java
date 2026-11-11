package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.RequestKind;
import com.sairo.be.domain.visit.entity.RequestStatus;
import com.sairo.be.domain.visit.entity.VisitRequest;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitRequestRepository extends JpaRepository<VisitRequest, Long> {

  Optional<VisitRequest> findByIdAndApplicantOfficeId(Long id, Long applicantOfficeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from VisitRequest r where r.id = :id")
  Optional<VisitRequest> findForUpdate(@Param("id") Long id);

  List<VisitRequest> findByCoordinationIdOrderBySubmittedAtDescIdDesc(Long coordinationId);

  List<VisitRequest> findByCoordinationIdAndStatusOrderBySubmittedAtDescIdDesc(
      Long coordinationId, RequestStatus status);

  boolean existsByCandidatePropertyIdAndStatusIn(
      Long candidatePropertyId, Collection<RequestStatus> statuses);

  long countByApplicantMembershipIdAndStatusIn(
      Long applicantMembershipId, Collection<RequestStatus> statuses);

  long countByApprovalIdAndStatusIn(Long approvalId, Collection<RequestStatus> statuses);

  long countByCoordinationIdAndRequestKindAndStatusIn(
      Long coordinationId, RequestKind requestKind, Collection<RequestStatus> statuses);
}
