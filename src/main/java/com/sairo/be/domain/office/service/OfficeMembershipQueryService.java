package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OfficeMembershipQueryService {

  private final OfficeMembershipRepository officeMembershipRepository;

  public Optional<ApprovedMembership> findApprovedMembership(Long userId) {
    return officeMembershipRepository
        .findByUserIdAndStatus(userId, OfficeMembershipStatus.APPROVED)
        .map(
            membership ->
                new ApprovedMembership(membership.getId(), membership.getOffice().getId()));
  }
}
