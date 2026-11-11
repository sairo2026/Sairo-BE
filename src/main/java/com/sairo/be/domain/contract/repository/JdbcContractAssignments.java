package com.sairo.be.domain.contract.repository;

import com.sairo.be.global.port.ContractAssignments;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class JdbcContractAssignments implements ContractAssignments {

  private final ContractRepository repository;

  public JdbcContractAssignments(ContractRepository repository) {
    this.repository = repository;
  }

  @Override
  public long countOpen(Long officeId, Long membershipId) {
    return repository.countOpenManaged(officeId, membershipId);
  }

  @Override
  public int handOverToOwner(Long officeId, Long fromMembershipId, Instant now) {
    return repository
        .ownerMembershipId(officeId)
        .filter(owner -> !owner.equals(fromMembershipId))
        .map(owner -> repository.reassignOpen(officeId, fromMembershipId, owner, now))
        .orElse(0);
  }
}
