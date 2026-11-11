package com.sairo.be.domain.contract.repository;

import com.sairo.be.global.port.ContractAssignments;
import com.sairo.be.global.port.ContractWorkHooks;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class JdbcContractAssignments implements ContractAssignments {

  private final ContractRepository repository;
  private final ContractWorkHooks workHooks;

  public JdbcContractAssignments(ContractRepository repository, ContractWorkHooks workHooks) {
    this.repository = repository;
    this.workHooks = workHooks;
  }

  @Override
  public long countOpen(Long officeId, Long membershipId) {
    return repository.countOpenManaged(officeId, membershipId);
  }

  /** 담당 계약을 대표에게 넘기고 그 계약의 자동 업무 담당자도 함께 바꾼다. */
  @Override
  public int handOverToOwner(Long officeId, Long fromMembershipId, Instant now) {
    Long owner = repository.ownerMembershipId(officeId).orElse(null);
    if (owner == null || owner.equals(fromMembershipId)) {
      return 0;
    }
    List<Long> ids = repository.openManagedIds(officeId, fromMembershipId);
    int changed = repository.reassignOpen(officeId, fromMembershipId, owner, now);
    ids.forEach(workHooks::contractChanged);
    return changed;
  }
}
