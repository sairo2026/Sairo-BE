package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.ConsentNoticeDelivery;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.NotificationChannel;
import com.sairo.be.domain.auth.entity.NotificationPreference;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.ConsentNoticeDeliveryRepository;
import com.sairo.be.domain.auth.repository.NotificationPreferenceRepository;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class MarketingConsentService {

  public static final String MARKETING = "MARKETING";

  private final TermsAgreementRepository termsAgreementRepository;
  private final NotificationPreferenceRepository preferenceRepository;
  private final ConsentNoticeDeliveryRepository noticeDeliveryRepository;

  public MarketingConsentService(
      TermsAgreementRepository termsAgreementRepository,
      NotificationPreferenceRepository preferenceRepository,
      ConsentNoticeDeliveryRepository noticeDeliveryRepository) {
    this.termsAgreementRepository = termsAgreementRepository;
    this.preferenceRepository = preferenceRepository;
    this.noticeDeliveryRepository = noticeDeliveryRepository;
  }

  public boolean isCurrentlyAgreed(Long userId) {
    return latest(userId).map(TermsAgreement::isAgreed).orElse(false);
  }

  public Optional<TermsAgreement> latest(Long userId) {
    return termsAgreementRepository.findFirstByUserIdAndTermsCodeOrderByIdDesc(userId, MARKETING);
  }

  public TermsAgreement record(
      Long userId,
      String version,
      boolean agreed,
      ConsentPath path,
      Instant now,
      boolean hasVerifiedEmail) {
    TermsAgreement agreement =
        termsAgreementRepository.save(
            TermsAgreement.record(userId, MARKETING, version, agreed, path, now));
    syncPreference(userId, agreed, now);
    noticeDeliveryRepository.save(
        ConsentNoticeDelivery.resultNotice(userId, agreement.getId(), now, hasVerifiedEmail));
    return agreement;
  }

  public void syncPreference(Long userId, boolean enabled, Instant now) {
    NotificationPreference preference =
        preferenceRepository
            .findByUserIdAndChannel(userId, NotificationChannel.MARKETING)
            .orElse(null);
    if (preference == null) {
      preferenceRepository.save(
          NotificationPreference.create(userId, NotificationChannel.MARKETING, enabled, now));
    } else {
      preference.change(enabled, now);
    }
  }
}
