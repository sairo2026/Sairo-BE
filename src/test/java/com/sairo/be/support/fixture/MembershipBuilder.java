package com.sairo.be.support.fixture;

import com.sairo.be.domain.office.entity.OfficeMembership;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.entity.OfficeRequestSource;

public final class MembershipBuilder {

  private final Long userId;
  private final Long officeId;
  private OfficeMembershipRole role = OfficeMembershipRole.AGENT;
  private OfficeRequestSource requestSource = OfficeRequestSource.REGISTRATION;

  private MembershipBuilder(Long userId, Long officeId) {
    this.userId = userId;
    this.officeId = officeId;
  }

  public static MembershipBuilder aMembership(Long userId, Long officeId) {
    return new MembershipBuilder(userId, officeId);
  }

  public MembershipBuilder role(OfficeMembershipRole role) {
    this.role = role;
    return this;
  }

  public MembershipBuilder requestSource(OfficeRequestSource requestSource) {
    this.requestSource = requestSource;
    return this;
  }

  public OfficeMembership build() {
    return OfficeMembership.approve(userId, officeId, role, requestSource);
  }
}
