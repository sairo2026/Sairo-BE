package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.legal.entity.KakaoUnlinkStatus;
import com.sairo.be.domain.legal.service.KakaoUnlinkEventService;
import com.sairo.be.global.security.SessionTerminator;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Component
public class KakaoUnlinkApplier {

  private final AppUserRepository appUserRepository;
  private final KakaoUnlinkEventService eventService;
  private final SessionTerminator sessionTerminator;
  private final KakaoProperties kakaoProperties;
  private final Clock clock;

  public KakaoUnlinkApplier(
      AppUserRepository appUserRepository,
      KakaoUnlinkEventService eventService,
      SessionTerminator sessionTerminator,
      KakaoProperties kakaoProperties,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.eventService = eventService;
    this.sessionTerminator = sessionTerminator;
    this.kakaoProperties = kakaoProperties;
    this.clock = clock;
  }

  @Transactional
  public Optional<String> apply(String appId, String kakaoUserId, String referrerType) {
    String expectedAppId = kakaoProperties.appId();
    if (expectedAppId == null || expectedAppId.isBlank() || !expectedAppId.equals(appId)) {
      eventService.recordInbound(null, kakaoUserId, referrerType, KakaoUnlinkStatus.NO_MATCH);
      return Optional.empty();
    }
    if (eventService.isDuplicateInbound(kakaoUserId, referrerType)) {
      eventService.recordInbound(
          null, kakaoUserId, referrerType, KakaoUnlinkStatus.DUPLICATE_IGNORED);
      return Optional.empty();
    }
    Optional<AppUser> found =
        appUserRepository.findByKakaoProviderKey(kakaoUserId).filter(AppUser::isActive);
    if (found.isEmpty()) {
      eventService.recordInbound(null, kakaoUserId, referrerType, KakaoUnlinkStatus.NO_MATCH);
      return Optional.empty();
    }
    AppUser user = found.get();
    boolean hasEmailLogin = user.hasEmailLogin();
    user.applyKakaoUnlinkedByProvider(clock.instant());
    if (!hasEmailLogin) {
      sessionTerminator.terminateAll(user.getId());
    }
    eventService.recordInbound(user.getId(), kakaoUserId, referrerType, KakaoUnlinkStatus.APPLIED);
    boolean canNotify = user.getEmail() != null && user.getEmailVerifiedAt() != null;
    return canNotify ? Optional.of(user.getEmail()) : Optional.empty();
  }
}
