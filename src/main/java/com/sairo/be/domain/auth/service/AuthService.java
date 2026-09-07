package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.office.service.ApprovedMembership;
import com.sairo.be.domain.office.service.OfficeMembershipQueryService;
import com.sairo.be.global.security.StaffPrincipal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

@Slf4j
@Profile("!migrate")
@Service
@RequiredArgsConstructor
public class AuthService {

  private final KakaoOAuthClient kakaoOAuthClient;
  private final AppUserRepository appUserRepository;
  private final OfficeMembershipQueryService officeMembershipQueryService;

  public String buildAuthorizeUrl(String state) {
    return kakaoOAuthClient.buildAuthorizeUrl(state);
  }

  @Transactional(readOnly = true)
  public Optional<StaffPrincipal> authenticate(String code) {
    Long kakaoUserId;
    try {
      String accessToken = kakaoOAuthClient.exchangeToken(code);
      kakaoUserId = kakaoOAuthClient.fetchUserId(accessToken);
    } catch (RestClientException e) {
      log.warn("카카오 인증 요청이 실패했습니다.", e);
      return Optional.empty();
    }

    return appUserRepository
        .findByKakaoProviderKey(String.valueOf(kakaoUserId))
        .flatMap(
            user ->
                officeMembershipQueryService
                    .findApprovedMembership(user.getId())
                    .map(membership -> toPrincipal(user.getId(), user.getName(), membership)));
  }

  private StaffPrincipal toPrincipal(Long userId, String name, ApprovedMembership membership) {
    return new StaffPrincipal(userId, membership.membershipId(), membership.officeId(), name);
  }
}
