package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.MarketingUnsubscribeResponse;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.ConsentNoticeDelivery;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class MarketingUnsubscribeService {

  private final MarketingUnsubscribeTokens tokens;
  private final AppUserRepository appUserRepository;
  private final MarketingConsentService marketingConsentService;
  private final TermsCatalogService termsCatalogService;
  private final RequestRateLimiter rateLimiter;
  private final Clock clock;

  public MarketingUnsubscribeService(
      MarketingUnsubscribeTokens tokens,
      AppUserRepository appUserRepository,
      MarketingConsentService marketingConsentService,
      TermsCatalogService termsCatalogService,
      RequestRateLimiter rateLimiter,
      Clock clock) {
    this.tokens = tokens;
    this.appUserRepository = appUserRepository;
    this.marketingConsentService = marketingConsentService;
    this.termsCatalogService = termsCatalogService;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
  }

  @Transactional
  public MarketingUnsubscribeResponse unsubscribe(String token, String clientIp) {
    rateLimiter.hit(AuthRateLimits.UNSUBSCRIBE_IP, clientIp);
    Long userId = tokens.verify(token).orElseThrow(this::invalidToken);
    AppUser user =
        appUserRepository
            .findById(userId)
            .filter(AppUser::isActive)
            .orElseThrow(this::invalidToken);
    Instant now = clock.instant();

    Optional<TermsAgreement> latest = marketingConsentService.latest(userId);
    if (latest.isEmpty() || !latest.get().isAgreed()) {
      Instant expressedAt = latest.map(TermsAgreement::getRecordedAt).orElse(now);
      return response("ALREADY_UNSUBSCRIBED", expressedAt, now, false);
    }
    String version =
        termsCatalogService
            .findInForceByCode(MarketingConsentService.MARKETING)
            .map(TermsVersionView::version)
            .or(() -> latest.map(TermsAgreement::getTermsVersion))
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    boolean hasEmail = user.getEmail() != null && user.getEmailVerifiedAt() != null;
    marketingConsentService.record(
        userId, version, false, ConsentPath.UNSUBSCRIBE_LINK, now, hasEmail);
    return response("UNSUBSCRIBED", now, now, hasEmail);
  }

  private MarketingUnsubscribeResponse response(
      String status, Instant expressedAt, Instant processedAt, boolean emailNoticeScheduled) {
    return new MarketingUnsubscribeResponse(
        status,
        TimeRules.toDisplay(expressedAt).toOffsetDateTime(),
        TimeRules.toDisplay(processedAt).toOffsetDateTime(),
        TimeRules.toDisplay(expressedAt.plus(ConsentNoticeDelivery.NOTICE_DEADLINE))
            .toOffsetDateTime(),
        emailNoticeScheduled);
  }

  private BusinessException invalidToken() {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST,
        List.of(new FieldErrorItem("token", FieldErrorCode.INVALID_VALUE, "수신 거부 링크가 올바르지 않습니다.")));
  }
}
