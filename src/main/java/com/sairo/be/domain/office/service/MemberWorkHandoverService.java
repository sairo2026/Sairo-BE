package com.sairo.be.domain.office.service;

import com.sairo.be.global.port.ContractAssignments;
import com.sairo.be.global.port.MemberWorkHandover;
import com.sairo.be.global.port.MemberWorkHandover.WorkKind;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구성원의 진행 중 고객·매물·업무·계약·방문 조율을 사무소의 승인된 대표에게 넘긴다(MEMBER-05, SETTING-02 ③). 대표가 없거나(사무소 종료 뒤) 대상이 대표
 * 본인이면 넘기지 않는다.
 */
@Profile("!migrate")
@Service
public class MemberWorkHandoverService {

  private final JdbcClient jdbcClient;
  private final ContractAssignments contractAssignments;
  private final List<MemberWorkHandover> handovers;

  public MemberWorkHandoverService(
      JdbcClient jdbcClient,
      ContractAssignments contractAssignments,
      List<MemberWorkHandover> handovers) {
    this.jdbcClient = jdbcClient;
    this.contractAssignments = contractAssignments;
    this.handovers = handovers;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int handOverToOwner(Long officeId, Long fromMembershipId, Instant now) {
    int changed = contractAssignments.handOverToOwner(officeId, fromMembershipId, now);
    Optional<Long> owner = ownerMembershipId(officeId);
    if (owner.isEmpty() || owner.get().equals(fromMembershipId)) {
      return changed;
    }
    for (MemberWorkHandover handover : handovers) {
      changed += handover.handOver(officeId, fromMembershipId, owner.get(), now);
    }
    return changed;
  }

  public long countOpen(WorkKind kind, Long officeId, Long membershipId) {
    return handovers.stream()
        .filter(handover -> handover.kind() == kind)
        .mapToLong(handover -> handover.countOpen(officeId, membershipId))
        .sum();
  }

  private Optional<Long> ownerMembershipId(Long officeId) {
    return jdbcClient
        .sql(
            "SELECT id FROM office_membership WHERE office_id = :office AND role = 'OWNER' AND"
                + " status = 'APPROVED'")
        .param("office", officeId)
        .query(Long.class)
        .optional();
  }
}
