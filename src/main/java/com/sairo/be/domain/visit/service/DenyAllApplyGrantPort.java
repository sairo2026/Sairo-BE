package com.sairo.be.domain.visit.service;

import com.sairo.be.global.port.ApplyGrantPort;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class DenyAllApplyGrantPort implements ApplyGrantPort {

  @Override
  public Optional<Long> findActiveGrantId(
      Long granteeOfficeId, Long granteeMembershipId, Long approvalId, Long propertyId) {
    return Optional.empty();
  }
}
