package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.office.service.OfficeMembershipQueryService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class MemberPrincipalFactory {

  private final OfficeMembershipQueryService officeMembershipQueryService;

  public MemberPrincipalFactory(OfficeMembershipQueryService officeMembershipQueryService) {
    this.officeMembershipQueryService = officeMembershipQueryService;
  }

  public StaffPrincipal create(AppUser user) {
    return officeMembershipQueryService
        .findApprovedMembership(user.getId())
        .map(
            membership ->
                new StaffPrincipal(
                    user.getId(),
                    membership.membershipId(),
                    membership.officeId(),
                    user.getName(),
                    StaffRole.valueOf(membership.role().name())))
        .orElseGet(() -> new StaffPrincipal(user.getId(), null, null, user.getName(), null));
  }
}
