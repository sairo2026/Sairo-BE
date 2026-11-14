package com.sairo.be.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class StaffPrincipalQueryServiceTest {

  @Autowired private StaffPrincipalQueryService service;
  @Autowired private AppUserRepository appUserRepository;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  @Test
  void 승인된_소속의_사무소와_역할을_인증_주체에_담는다() {
    TestData data = new TestData(appUserRepository, officeRepository, officeMembershipRepository);
    TestData.Staff owner = data.approvedStaff(OfficeMembershipRole.OWNER);
    TestData.Staff deputy = data.approvedStaff(OfficeMembershipRole.DEPUTY);

    var ownerPrincipal =
        service.findByKakaoUserId(Long.valueOf(owner.user().getKakaoProviderKey()));
    var deputyPrincipal =
        service.findByKakaoUserId(Long.valueOf(deputy.user().getKakaoProviderKey()));

    assertThat(ownerPrincipal).contains(owner.principal());
    assertThat(ownerPrincipal.get().role()).isEqualTo(StaffRole.OWNER);
    assertThat(ownerPrincipal.get().officeId()).isEqualTo(owner.office().getId());
    assertThat(deputyPrincipal.get().role()).isEqualTo(StaffRole.DEPUTY);
  }
}
