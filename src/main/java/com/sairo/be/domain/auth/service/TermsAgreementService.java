package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.request.AgreedTermRequest;
import com.sairo.be.domain.auth.dto.request.TermsAgreementRequest;
import com.sairo.be.domain.auth.dto.response.ChangedTermsItem;
import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.legal.entity.TermsCategory;
import com.sairo.be.domain.legal.entity.TermsDisplayContext;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class TermsAgreementService {

  private static final String FIELD_AGREED_TERMS = "agreedTerms";

  private final TermsCatalogService termsCatalogService;
  private final TermsAgreementRepository termsAgreementRepository;
  private final AppUserRepository appUserRepository;
  private final MarketingConsentService marketingConsentService;
  private final Clock clock;

  public TermsAgreementService(
      TermsCatalogService termsCatalogService,
      TermsAgreementRepository termsAgreementRepository,
      AppUserRepository appUserRepository,
      MarketingConsentService marketingConsentService,
      Clock clock) {
    this.termsCatalogService = termsCatalogService;
    this.termsAgreementRepository = termsAgreementRepository;
    this.appUserRepository = appUserRepository;
    this.marketingConsentService = marketingConsentService;
    this.clock = clock;
  }

  public List<TermsVersionView> findCatalog(String context) {
    TermsDisplayContext displayContext;
    try {
      displayContext = TermsDisplayContext.valueOf(context);
    } catch (IllegalArgumentException e) {
      throw invalid("context", FieldErrorCode.INVALID_VALUE, "허용되지 않는 context 값입니다.");
    }
    return termsCatalogService.findInForce(displayContext);
  }

  public List<TermsVersionView> validateSignupAgreements(
      List<AgreedTermRequest> submitted, boolean hasVerifiedEmail) {
    List<TermsVersionView> inForce = termsCatalogService.findInForce(TermsDisplayContext.SIGNUP);
    Map<String, TermsVersionView> byCode = new LinkedHashMap<>();
    inForce.forEach(view -> byCode.put(view.termsCode(), view));

    Set<String> seen = new HashSet<>();
    List<TermsVersionView> accepted = new ArrayList<>();
    List<ChangedTermsItem> changed = new ArrayList<>();
    for (AgreedTermRequest item : submitted) {
      TermsVersionView current = byCode.get(item.termsCode());
      if (current == null || !current.isAgreeable() || !seen.add(item.termsCode())) {
        throw invalid(FIELD_AGREED_TERMS, FieldErrorCode.NOT_ALLOWED, "동의할 수 없는 약관 항목입니다.");
      }
      accepted.add(current);
      if (!current.version().equals(item.version())) {
        changed.add(
            new ChangedTermsItem(
                current.termsCode(),
                item.version(),
                current.version(),
                current.changeSummary(),
                current.documentUrl()));
      }
    }
    boolean missingRequired =
        inForce.stream()
            .anyMatch(
                view -> view.isAgreeable() && view.required() && !seen.contains(view.termsCode()));
    if (missingRequired) {
      throw invalid(FIELD_AGREED_TERMS, FieldErrorCode.REQUIRED, "필수 약관에 모두 동의해야 합니다.");
    }
    if (!changed.isEmpty()) {
      throw BusinessException.termsVersionChanged(changed);
    }
    boolean marketingSubmitted =
        accepted.stream().anyMatch(view -> view.category() == TermsCategory.MARKETING);
    if (marketingSubmitted && !hasVerifiedEmail) {
      throw new BusinessException(ErrorCode.MARKETING_EMAIL_REQUIRED);
    }
    return accepted;
  }

  public void recordSignupAgreements(
      Long userId,
      List<TermsVersionView> accepted,
      ConsentPath path,
      Instant now,
      boolean hasVerifiedEmail) {
    for (TermsVersionView view : accepted) {
      if (view.category() == TermsCategory.MARKETING) {
        marketingConsentService.record(userId, view.version(), true, path, now, hasVerifiedEmail);
      } else {
        termsAgreementRepository.save(
            TermsAgreement.record(userId, view.termsCode(), view.version(), true, path, now));
      }
    }
  }

  @Transactional
  public void recordAgreement(Long userId, TermsAgreementRequest request) {
    if (request.consentPath() != ConsentPath.ACCOUNT_SETTINGS
        && request.consentPath() != ConsentPath.RECONSENT) {
      throw invalid("consentPath", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 동의 경로입니다.");
    }
    TermsVersionView current =
        termsCatalogService
            .findInForceByCode(request.termsCode())
            .orElseThrow(
                () -> invalid("termsCode", FieldErrorCode.INVALID_VALUE, "알 수 없는 약관 코드입니다."));
    if (!current.isAgreeable()) {
      if (!request.agreed()) {
        throw new BusinessException(ErrorCode.TERMS_WITHDRAWAL_NOT_ALLOWED);
      }
      throw invalid("termsCode", FieldErrorCode.NOT_ALLOWED, "동의 대상이 아닌 항목입니다.");
    }
    AppUser user = appUserRepository.findById(userId).orElseThrow(this::unauthenticated);
    Instant now = clock.instant();
    boolean agreed = request.agreed();
    if (!agreed) {
      if (current.required()) {
        throw new BusinessException(ErrorCode.TERMS_WITHDRAWAL_NOT_ALLOWED);
      }
      record(user, current, false, request.consentPath(), now);
      return;
    }
    if (!current.version().equals(request.version())) {
      throw BusinessException.termsVersionChanged(
          List.of(
              new ChangedTermsItem(
                  current.termsCode(),
                  request.version(),
                  current.version(),
                  current.changeSummary(),
                  current.documentUrl())));
    }
    if (current.category() == TermsCategory.MARKETING && !hasVerifiedEmail(user)) {
      throw new BusinessException(ErrorCode.MARKETING_EMAIL_REQUIRED);
    }
    record(user, current, true, request.consentPath(), now);
  }

  private void record(
      AppUser user, TermsVersionView current, boolean agreed, ConsentPath path, Instant now) {
    Optional<TermsAgreement> latest =
        termsAgreementRepository.findFirstByUserIdAndTermsCodeOrderByIdDesc(
            user.getId(), current.termsCode());
    boolean unchanged =
        latest.isPresent()
            && latest.get().isAgreed() == agreed
            && latest.get().getTermsVersion().equals(current.version());
    if (unchanged) {
      return;
    }
    if (current.category() == TermsCategory.MARKETING) {
      marketingConsentService.record(
          user.getId(), current.version(), agreed, path, now, hasVerifiedEmail(user));
      return;
    }
    termsAgreementRepository.save(
        TermsAgreement.record(
            user.getId(), current.termsCode(), current.version(), agreed, path, now));
  }

  private boolean hasVerifiedEmail(AppUser user) {
    return user.getEmail() != null && user.getEmailVerifiedAt() != null;
  }

  private BusinessException unauthenticated() {
    return new BusinessException(ErrorCode.UNAUTHENTICATED);
  }

  private BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
