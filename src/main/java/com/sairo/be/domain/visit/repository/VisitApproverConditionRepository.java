package com.sairo.be.domain.visit.repository;

import com.sairo.be.domain.visit.entity.ConditionStatus;
import com.sairo.be.domain.visit.entity.VisitApproverCondition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitApproverConditionRepository
    extends JpaRepository<VisitApproverCondition, Long> {

  Optional<VisitApproverCondition> findFirstByApprovalIdAndStatus(
      Long approvalId, ConditionStatus status);

  List<VisitApproverCondition> findByApprovalIdOrderByVersionNoDesc(Long approvalId);

  @Query(
      "select coalesce(max(c.versionNo), 0) from VisitApproverCondition c"
          + " where c.approvalId = :approvalId")
  int maxVersionNo(@Param("approvalId") Long approvalId);
}
