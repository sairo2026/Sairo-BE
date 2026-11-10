package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.AccountStatus;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.AuthMethod;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.TokenGenerator;
import com.sairo.be.global.web.ClientRequestInfo;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class KakaoLoginService {

  public static final Duration GRANT_CREDENTIAL_VALIDITY = Duration.ofMinutes(5);
  public static final String GRANT_CREDENTIAL_PREFIX = "sairo:grant-credential:";

  private final AppUserRepository appUserRepository;
  private final LoginHistoryService loginHistoryService;
  private final MemberPrincipalFactory principalFactory;
  private final StringRedisTemplate redis;
  private final TokenGenerator tokenGenerator;
  private final Clock clock;

  public KakaoLoginService(
      AppUserRepository appUserRepository,
      LoginHistoryService loginHistoryService,
      MemberPrincipalFactory principalFactory,
      StringRedisTemplate redis,
      TokenGenerator tokenGenerator,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.loginHistoryService = loginHistoryService;
    this.principalFactory = principalFactory;
    this.redis = redis;
    this.tokenGenerator = tokenGenerator;
    this.clock = clock;
  }

  @Transactional
  public Optional<KakaoLoginOutcome> loginOrRegister(String providerKey, ClientRequestInfo client) {
    Optional<AppUser> existing = appUserRepository.findByKakaoProviderKey(providerKey);
    AppUser user;
    if (existing.isPresent()) {
      user = existing.get();
      if (user.getAccountStatus() != AccountStatus.ACTIVE) {
        return Optional.empty();
      }
      if (user.getKakaoUnlinkedAt() != null) {
        user.reconnectKakao();
      }
    } else {
      try {
        user =
            appUserRepository.saveAndFlush(
                AppUser.registerPendingKakao(providerKey, clock.instant()));
      } catch (DataIntegrityViolationException e) {
        return Optional.empty();
      }
    }
    loginHistoryService.record(user.getId(), AuthMethod.KAKAO, client);
    StaffPrincipal principal = principalFactory.create(user);
    return Optional.of(new KakaoLoginOutcome(principal, user.isSignupCompleted()));
  }

  public String issueGrantCredential(String providerKey) {
    String credential = tokenGenerator.randomToken();
    redis
        .opsForValue()
        .set(GRANT_CREDENTIAL_PREFIX + credential, providerKey, GRANT_CREDENTIAL_VALIDITY);
    return credential;
  }

  public record KakaoLoginOutcome(StaffPrincipal principal, boolean signupCompleted) {}
}
