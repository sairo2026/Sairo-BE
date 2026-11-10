package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.MeResponse;
import com.sairo.be.domain.auth.dto.response.MeResponse.NotificationPreferencesResponse;
import com.sairo.be.domain.auth.dto.response.MeResponse.TermsAgreementSummaryResponse;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.NotificationChannel;
import com.sairo.be.domain.auth.entity.NotificationPreference;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.repository.NotificationPreferenceRepository;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.legal.entity.TermsCategory;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.TimeRules;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class MeQueryService {

  private final AppUserRepository appUserRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final TermsAgreementRepository termsAgreementRepository;
  private final TermsCatalogService termsCatalogService;

  public MeQueryService(
      AppUserRepository appUserRepository,
      NotificationPreferenceRepository preferenceRepository,
      TermsAgreementRepository termsAgreementRepository,
      TermsCatalogService termsCatalogService) {
    this.appUserRepository = appUserRepository;
    this.preferenceRepository = preferenceRepository;
    this.termsAgreementRepository = termsAgreementRepository;
    this.termsCatalogService = termsCatalogService;
  }

  public MeResponse getMe(Long userId) {
    AppUser user =
        appUserRepository
            .findById(userId)
            .filter(AppUser::isActive)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    return toResponse(user);
  }

  public NotificationPreferencesResponse notificationPreferences(Long userId) {
    Map<NotificationChannel, Boolean> values = new EnumMap<>(NotificationChannel.class);
    for (NotificationChannel channel : NotificationChannel.values()) {
      values.put(channel, channel.defaultEnabled());
    }
    for (NotificationPreference preference : preferenceRepository.findByUserId(userId)) {
      values.put(preference.getChannel(), preference.isEnabled());
    }
    values.put(NotificationChannel.SECURITY, true);
    Map<String, Boolean> channels = new LinkedHashMap<>();
    channels.put("VISIT_REMINDER", values.get(NotificationChannel.VISIT_REMINDER));
    channels.put("MARKETING", values.get(NotificationChannel.MARKETING));
    channels.put("SECURITY", values.get(NotificationChannel.SECURITY));
    return new NotificationPreferencesResponse(channels, List.of("SECURITY"));
  }

  public MeResponse toResponse(AppUser user) {
    List<String> loginMethods = new ArrayList<>();
    if (user.hasEmailLogin()) {
      loginMethods.add("EMAIL");
    }
    if (user.hasKakaoLogin()) {
      loginMethods.add("KAKAO");
    }
    return new MeResponse(
        user.getId(),
        user.getName(),
        user.getEmail(),
        user.getEmailVerifiedAt() != null,
        user.getPhone(),
        user.getPhoneVerifiedAt() != null,
        user.hasKakaoLogin(),
        loginMethods,
        user.getAccountStatus().name(),
        notificationPreferences(user.getId()),
        TimeRules.toDisplay(user.getCreatedAt()).toOffsetDateTime(),
        termsAgreementSummaries(user.getId()));
  }

  private List<TermsAgreementSummaryResponse> termsAgreementSummaries(Long userId) {
    List<TermsAgreementSummaryResponse> summaries = new ArrayList<>();
    for (TermsAgreement agreement : termsAgreementRepository.findLatestPerCode(userId)) {
      TermsVersionView catalog =
          termsCatalogService
              .findVersion(agreement.getTermsCode(), agreement.getTermsVersion())
              .orElse(null);
      if (catalog == null) {
        continue;
      }
      boolean withdrawable = !catalog.required() && catalog.category() != TermsCategory.NOTICE;
      summaries.add(
          new TermsAgreementSummaryResponse(
              agreement.getTermsCode(),
              catalog.title(),
              catalog.required(),
              withdrawable,
              agreement.getTermsVersion(),
              agreement.isAgreed(),
              TimeRules.toDisplay(agreement.getRecordedAt()).toOffsetDateTime()));
    }
    return summaries;
  }
}
