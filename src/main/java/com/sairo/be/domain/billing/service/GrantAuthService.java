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
    verifyCredential(user, request, clientIp);
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

  /**
   * 권한 ID만 알면 누구나 시도할 수 있으므로 계정 잠금을 쓰지 않는다. 같은 접속지의 연속 실패는 그 접속지만 거절하고, 접속지를 바꿔 실패가 크게 쌓인
   * 권한 ID는 잠그지 않고 시도 간격만 늘려 정상 권한자가 간격 안에 다시 시도하면 통과하게 한다. 이미 한도를 넘은 접속지는 간격 슬롯을 쓰지 못한다.
   */
  private void throttleBeforeCheck(String grantKey, String ipKey) {
    RequestRateLimiter.Result mine = rateLimiter.peek(BillingRateLimits.GRANT_AUTH_FAILURE_IP, ipKey);
    if (mine.count() >= BillingRateLimits.GRANT_AUTH_FAILURE_IP.limit()) {
      throw BusinessException.rateLimited(mine.retryAfterSeconds());
    }
    RequestRateLimiter.Result all =
        rateLimiter.peek(BillingRateLimits.GRANT_AUTH_FAILURE_GLOBAL, grantKey);
    if (all.count() >= BillingRateLimits.GRANT_AUTH_FAILURE_GLOBAL.limit()) {
      rateLimiter.hit(BillingRateLimits.GRANT_AUTH_PACE, grantKey);
    }
  }

  private void verifyCredential(AppUser user, GrantAuthRequest request, String clientIp) {
    if (request.authMethod() == GrantAuthRequest.AuthMethod.EMAIL) {
      if (user.getPasswordHash() == null) {
        throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
      }
      String grantKey = String.valueOf(request.grantId());
      String ipKey = grantKey + "|" + clientIp;
      throttleBeforeCheck(grantKey, ipKey);
      if (!passwordHasher.matches(request.credential(), user.getPasswordHash())) {
        rateLimiter.increment(BillingRateLimits.GRANT_AUTH_FAILURE_IP, ipKey);
        rateLimiter.increment(BillingRateLimits.GRANT_AUTH_FAILURE_GLOBAL, grantKey);
        throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
      }
      rateLimiter.reset(BillingRateLimits.GRANT_AUTH_FAILURE_IP, ipKey);
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
