package com.sairo.be.domain.billing.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.service.KakaoLoginService;
import com.sairo.be.domain.auth.service.PasswordHasher;
import com.sairo.be.domain.billing.dto.request.GrantAuthRequest;
import com.sairo.be.domain.billing.dto.response.GrantAuthResponse;
import com.sairo.be.domain.billing.entity.ArchiveGrant;
import com.sairo.be.domain.billing.entity.ReturnGrant;
import com.sairo.be.domain.billing.repository.ArchiveGrantRepository;
import com.sairo.be.domain.billing.repository.ReturnGrantRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.GrantPrincipal;
import com.sairo.be.global.security.GrantSessionManager;
import com.sairo.be.global.security.GrantType;
import com.sairo.be.global.support.RequestRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class GrantAuthService {

  private final ReturnGrantRepository returnGrantRepository;
  private final ArchiveGrantRepository archiveGrantRepository;
  private final AppUserRepository appUserRepository;
  private final PasswordHasher passwordHasher;
  private final StringRedisTemplate redis;
  private final RequestRateLimiter rateLimiter;
  private final GrantSessionManager sessionManager;
  private final Clock clock;

  public GrantAuthService(
      ReturnGrantRepository returnGrantRepository,
      ArchiveGrantRepository archiveGrantRepository,
      AppUserRepository appUserRepository,
      PasswordHasher passwordHasher,
      StringRedisTemplate redis,
      RequestRateLimiter rateLimiter,
      GrantSessionManager sessionManager,
      Clock clock) {
    this.returnGrantRepository = returnGrantRepository;
    this.archiveGrantRepository = archiveGrantRepository;
    this.appUserRepository = appUserRepository;
    this.passwordHasher = passwordHasher;
    this.redis = redis;
    this.rateLimiter = rateLimiter;
    this.sessionManager = sessionManager;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public GrantAuthResponse authenticate(
      GrantType type,
      GrantAuthRequest request,
      String clientIp,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    rateLimiter.hit(BillingRateLimits.GRANT_AUTH_IP, clientIp);
    GrantRow grant = load(type, request.grantId());
    AppUser user =
        appUserRepository
            .findById(grant.userId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    verifyCredential(user, request);
    if (!grant.usable()) {
      throw new BusinessException(
          type == GrantType.RETURN
              ? ErrorCode.RETURN_GRANT_INVALID
              : ErrorCode.ARCHIVE_GRANT_INVALID);
    }
    sessionManager.begin(
        httpRequest,
        httpResponse,
        new GrantPrincipal(user.getId(), request.grantId(), type, grant.officeId()));
    return new GrantAuthResponse(
        request.grantId(), type.name(), toDisplayOffset(grant.expiresAt()));
  }

  private GrantRow load(GrantType type, Long grantId) {
    if (type == GrantType.RETURN) {
      ReturnGrant grant =
          returnGrantRepository
              .findById(grantId)
              .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
      return new GrantRow(
          grant.getGrantedToUserId(),
          grant.getOfficeId(),
          grant.getExpiresAt(),
          grant.isUsable(clock.instant()));
    }
    ArchiveGrant grant =
        archiveGrantRepository
            .findById(grantId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    return new GrantRow(
        grant.getGrantedToUserId(),
        grant.getOfficeId(),
        grant.getExpiresAt(),
        grant.isUsable(clock.instant()));
  }

  private void verifyCredential(AppUser user, GrantAuthRequest request) {
    if (request.authMethod() == GrantAuthRequest.AuthMethod.EMAIL) {
      boolean matches =
          user.getPasswordHash() != null
              && passwordHasher.matches(request.credential(), user.getPasswordHash());
      if (!matches) {
        throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
      }
      return;
    }
    String providerKey =
        redis
            .opsForValue()
            .getAndDelete(KakaoLoginService.GRANT_CREDENTIAL_PREFIX + request.credential());
    if (providerKey == null) {
      throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
    }
    if (user.getKakaoProviderKey() == null || !user.getKakaoProviderKey().equals(providerKey)) {
      throw new BusinessException(ErrorCode.STEP_UP_ACCOUNT_MISMATCH);
    }
  }

  private record GrantRow(Long userId, Long officeId, Instant expiresAt, boolean usable) {}
}
