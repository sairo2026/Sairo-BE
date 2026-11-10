package com.sairo.be.support.fixture;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembership;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;

public final class TestData {

  private final AppUserRepository appUserRepository;
  private final OfficeRepository officeRepository;
  private final OfficeMembershipRepository officeMembershipRepository;

  public TestData(
      AppUserRepository appUserRepository,
      OfficeRepository officeRepository,
      OfficeMembershipRepository officeMembershipRepository) {
    this.appUserRepository = appUserRepository;
    this.officeRepository = officeRepository;
    this.officeMembershipRepository = officeMembershipRepository;
  }

  public Staff approvedStaff(OfficeMembershipRole role) {
    AppUser user = appUserRepository.saveAndFlush(AppUserBuilder.anAppUser().build());
    Office office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    return approvedStaffIn(office, user, role);
  }

  public Staff approvedStaffIn(Office office, AppUser user, OfficeMembershipRole role) {
    OfficeMembership membership =
        officeMembershipRepository.saveAndFlush(
            MembershipBuilder.aMembership(user.getId(), office.getId()).role(role).build());
    return new Staff(user, office, membership);
  }

  public record Staff(AppUser user, Office office, OfficeMembership membership) {

    public StaffPrincipal principal() {
      return new StaffPrincipal(
          user.getId(),
          membership.getId(),
          office.getId(),
          user.getName(),
          StaffRole.valueOf(membership.getRole().name()));
    }
  }
}
