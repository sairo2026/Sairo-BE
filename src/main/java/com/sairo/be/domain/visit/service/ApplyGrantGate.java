package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.repository.ApplyGrantRepository;
import com.sairo.be.global.port.ApplyGrantPort;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 신청 허용 표로 다른 사무소 매물 신청의 근거 허용을 찾고 매물 보관 때 허용을 회수한다. */
@Profile("!migrate")
@Component
public class ApplyGrantGate implements ApplyGrantPort {

  private final ApplyGrantRepository grantRepository;
  private final ApplyGrantService applyGrantService;
  private final Clock clock;

  public ApplyGrantGate(
      ApplyGrantRepository grantRepository, ApplyGrantService applyGrantService, Clock clock) {
    this.grantRepository = grantRepository;
    this.applyGrantService = applyGrantService;
    this.clock = clock;
  }

  @Override
  public Optional<Long> findActiveGrantId(
      Long granteeOfficeId, Long granteeMembershipId, Long approvalId, Long propertyId) {
    return grantRepository.findActiveGrantId(
        granteeOfficeId, granteeMembershipId, approvalId, propertyId, clock.instant());
  }

  @Override
  public int revokeForArchivedProperty(Long propertyId) {
    return applyGrantService.revokeForArchivedProperty(propertyId);
  }
}
