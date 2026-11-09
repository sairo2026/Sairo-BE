package com.sairo.be.domain.office.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class OfficeEntityPersistenceTest {

  @Autowired private AppUserRepository appUserRepository;
  @Autowired private OfficeRepository officeRepository;
  @Autowired private OfficeMembershipRepository officeMembershipRepository;

  @Test
  void 사용자_사무소_소속을_새_스키마에_저장하고_조회한다() {
    AppUser user = appUserRepository.saveAndFlush(AppUser.registerWithKakao("900001", "홍길동"));
    Office office =
        officeRepository.saveAndFlush(
            Office.register(
                "테스트공인중개사사무소",
                OfficeEntityType.INDIVIDUAL,
                "서울특별시 종로구",
                "11110-2026-00001",
                "1234567890",
                "홍길동",
                "010-0000-0000",
                "서울특별시 종로구 테스트로 1"));
    OfficeMembership membership =
        officeMembershipRepository.saveAndFlush(
            OfficeMembership.approve(
                user.getId(),
                office.getId(),
                OfficeMembershipRole.OWNER,
                OfficeRequestSource.REGISTRATION));

    assertThat(office.getVersion()).isEqualTo(1L);
    assertThat(membership.getVersion()).isEqualTo(1L);
    assertThat(
            officeMembershipRepository.findByUserIdAndStatus(
                user.getId(), OfficeMembershipStatus.APPROVED))
        .get()
        .extracting(OfficeMembership::getOfficeId)
        .isEqualTo(office.getId());
    assertThat(user.isActive()).isTrue();
  }
}
