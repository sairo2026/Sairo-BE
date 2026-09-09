package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembership;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.repository.OfficeMembershipRepository;
import com.sairo.be.domain.office.repository.OfficeRepository;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Profile("!migrate")
@Service
public class PilotStaffAutoApprovalService {

  private static final String PILOT_OFFICE_BUSINESS_REGISTRATION_NUMBER = "0000000000";
  private static final String PILOT_OFFICE_REAL_ESTATE_LICENSE_NUMBER = "PILOT0000000000000000000";
  private static final String PILOT_OFFICE_NAME = "사이로 임시사무소";
  private static final String PILOT_OFFICE_REPRESENTATIVE_NAME = "사이로";
  private static final String PILOT_OFFICE_PHONE = "000-0000-0000";
  private static final String PILOT_OFFICE_ADDRESS = "파일럿 테스트 임시 주소";
  private static final String PILOT_USER_NAME = "파일럿 사용자";

  private final AppUserRepository appUserRepository;
  private final OfficeRepository officeRepository;
  private final OfficeMembershipRepository officeMembershipRepository;

  public PilotStaffAutoApprovalService(
      AppUserRepository appUserRepository,
      OfficeRepository officeRepository,
      OfficeMembershipRepository officeMembershipRepository) {
    this.appUserRepository = appUserRepository;
    this.officeRepository = officeRepository;
    this.officeMembershipRepository = officeMembershipRepository;
  }

  @Transactional
  public Optional<StaffPrincipal> autoApprove(Long kakaoUserId) {
    String providerKey = String.valueOf(kakaoUserId);
    if (appUserRepository.findByKakaoProviderKey(providerKey).isPresent()) {
      return Optional.empty();
    }

    AppUser user = appUserRepository.save(AppUser.register(providerKey, PILOT_USER_NAME));
    Office office = findOrCreatePilotOffice();
    OfficeMembership membership =
        officeMembershipRepository.save(
            OfficeMembership.approve(user.getId(), office, OfficeMembershipRole.STAFF));

    log.warn("파일럿 자동 승인으로 카카오 사용자 id={}를 사무소 id={}의 STAFF로 등록했습니다.", kakaoUserId, office.getId());

    return Optional.of(
        new StaffPrincipal(user.getId(), membership.getId(), office.getId(), user.getName()));
  }

  private Office findOrCreatePilotOffice() {
    return officeRepository
        .findByBusinessRegistrationNumber(PILOT_OFFICE_BUSINESS_REGISTRATION_NUMBER)
        .orElseGet(
            () -> {
              try {
                return officeRepository.saveAndFlush(
                    Office.register(
                        PILOT_OFFICE_NAME,
                        PILOT_OFFICE_REPRESENTATIVE_NAME,
                        PILOT_OFFICE_BUSINESS_REGISTRATION_NUMBER,
                        PILOT_OFFICE_REAL_ESTATE_LICENSE_NUMBER,
                        PILOT_OFFICE_PHONE,
                        PILOT_OFFICE_ADDRESS));
              } catch (DataIntegrityViolationException e) {
                return officeRepository
                    .findByBusinessRegistrationNumber(PILOT_OFFICE_BUSINESS_REGISTRATION_NUMBER)
                    .orElseThrow(() -> e);
              }
            });
  }
}
