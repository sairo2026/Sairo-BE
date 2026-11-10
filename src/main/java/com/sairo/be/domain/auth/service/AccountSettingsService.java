package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.MeUpdateResponse;
import com.sairo.be.domain.auth.dto.response.NotificationPreferenceUpdateResponse;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.NotificationChannel;
import com.sairo.be.domain.auth.entity.NotificationPreference;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.repository.NotificationPreferenceRepository;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import com.sairo.be.global.support.InputRules;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class AccountSettingsService {

  private final AppUserRepository appUserRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final MarketingConsentService marketingConsentService;
  private final TermsCatalogService termsCatalogService;
  private final MeQueryService meQueryService;
  private final Clock clock;

  public AccountSettingsService(
      AppUserRepository appUserRepository,
      NotificationPreferenceRepository preferenceRepository,
      MarketingConsentService marketingConsentService,
      TermsCatalogService termsCatalogService,
      MeQueryService meQueryService,
      Clock clock) {
    this.appUserRepository = appUserRepository;
    this.preferenceRepository = preferenceRepository;
    this.marketingConsentService = marketingConsentService;
    this.termsCatalogService = termsCatalogService;
    this.meQueryService = meQueryService;
    this.clock = clock;
  }

  @Transactional
  public MeUpdateResponse updateDisplayName(Long userId, String rawName) {
    String name =
        InputRules.requireText("displayName", rawName, 1, EmailSignupService.DISPLAY_NAME_MAX);
    AppUser user = activeUser(userId);
    user.changeName(name);
    return new MeUpdateResponse(
        user.getId(),
        user.getName(),
        user.getEmail(),
        user.getEmailVerifiedAt() != null,
        user.getPhone(),
        user.getPhoneVerifiedAt() != null,
        user.hasKakaoLogin(),
        user.getAccountStatus().name(),
        TimeRules.toDisplay(user.getCreatedAt()).toOffsetDateTime());
  }

  @Transactional
  public NotificationPreferenceUpdateResponse updateNotificationPreferences(
      Long userId, Map<String, Boolean> requested) {
    AppUser user = activeUser(userId);
    Instant now = clock.instant();
    for (Map.Entry<String, Boolean> entry : requested.entrySet()) {
      NotificationChannel channel = parseChannel(entry.getKey());
      boolean enabled = entry.getValue() != null && entry.getValue();
      if (channel == NotificationChannel.MARKETING && enabled) {
        throw invalid(
            "channels.MARKETING", FieldErrorCode.NOT_ALLOWED, "마케팅 수신은 약관 동의로만 켤 수 있습니다.");
      }
      if (entry.getValue() == null) {
        throw invalid(
            "channels." + entry.getKey(), FieldErrorCode.REQUIRED, "true 또는 false여야 합니다.");
      }
      if (channel == NotificationChannel.MARKETING) {
        withdrawMarketing(user, now);
      } else if (channel != NotificationChannel.SECURITY) {
        upsert(userId, channel, enabled, now);
      }
    }
    return new NotificationPreferenceUpdateResponse(
        meQueryService.notificationPreferences(userId).channels());
  }

  private void withdrawMarketing(AppUser user, Instant now) {
    Optional<TermsAgreement> latest = marketingConsentService.latest(user.getId());
    boolean agreed = latest.map(TermsAgreement::isAgreed).orElse(false);
    if (!agreed) {
      marketingConsentService.syncPreference(user.getId(), false, now);
      return;
    }
    String version =
        termsCatalogService
            .findInForceByCode(MarketingConsentService.MARKETING)
            .map(TermsVersionView::version)
            .orElse(latest.get().getTermsVersion());
    boolean hasEmail = user.getEmail() != null && user.getEmailVerifiedAt() != null;
    marketingConsentService.record(
        user.getId(),
        version,
        false,
        com.sairo.be.domain.auth.entity.ConsentPath.ACCOUNT_SETTINGS,
        now,
        hasEmail);
  }

  private void upsert(Long userId, NotificationChannel channel, boolean enabled, Instant now) {
    NotificationPreference preference =
        preferenceRepository.findByUserIdAndChannel(userId, channel).orElse(null);
    if (preference == null) {
      preferenceRepository.save(NotificationPreference.create(userId, channel, enabled, now));
    } else {
      preference.change(enabled, now);
    }
  }

  private NotificationChannel parseChannel(String key) {
    try {
      return NotificationChannel.valueOf(key);
    } catch (IllegalArgumentException e) {
      throw invalid("channels." + key, FieldErrorCode.NOT_ALLOWED, "알 수 없는 알림 설정 키입니다.");
    }
  }

  private AppUser activeUser(Long userId) {
    return appUserRepository
        .findById(userId)
        .filter(AppUser::isActive)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }

  private BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
