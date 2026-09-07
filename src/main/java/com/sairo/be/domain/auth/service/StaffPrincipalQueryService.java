package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.service.OfficeMembershipQueryService;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@RequiredArgsConstructor
public class StaffPrincipalQueryService {

  private final AppUserRepository appUserRepository;
  private final OfficeMembershipQueryService officeMembershipQueryService;

  @Transactional(readOnly = true)
  public Optional<StaffPrincipal> findByKakaoUserId(Long kakaoUserId) {
    return appUserRepository
        .findActiveByKakaoProviderKey(String.valueOf(kakaoUserId))
        .flatMap(
            user ->
                officeMembershipQueryService
                    .findApprovedMembership(user.getId())
                    .map(
                        membership ->
                            new StaffPrincipal(
                                user.getId(),
                                membership.membershipId(),
                                membership.officeId(),
                                user.getName())));
  }
}
