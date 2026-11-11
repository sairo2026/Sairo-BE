package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.repository.ApplyGrantRepository;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 다른 사무소 매물을 후보로 담을 때 그 매물의 대표 승인자 출입승인에 활성 허용이 있는지 본다. */
@Profile("!migrate")
@Component
public class CrossOfficeGrantGate implements CrossOfficeCandidateGate {

  private final ApplyGrantRepository grantRepository;
  private final Clock clock;

  public CrossOfficeGrantGate(ApplyGrantRepository grantRepository, Clock clock) {
    this.grantRepository = grantRepository;
    this.clock = clock;
  }

  @Override
  public boolean isAllowed(
      Long granteeOfficeId, Long granteeMembershipId, Long propertyId, Long propertyOfficeId) {
    return grantRepository.hasActiveForProperty(
        granteeOfficeId, granteeMembershipId, propertyId, propertyOfficeId, clock.instant());
  }
}
