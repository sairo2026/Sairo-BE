package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.AppUserRepository;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.security.ReconsentStatusProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class ReconsentService implements ReconsentStatusProvider {

  private final TermsCatalogService termsCatalogService;
  private final TermsAgreementRepository termsAgreementRepository;
  private final AppUserRepository appUserRepository;

  public ReconsentService(
      TermsCatalogService termsCatalogService,
      TermsAgreementRepository termsAgreementRepository,
      AppUserRepository appUserRepository) {
    this.termsCatalogService = termsCatalogService;
    this.termsAgreementRepository = termsAgreementRepository;
    this.appUserRepository = appUserRepository;
  }

  @Override
  public boolean isReconsentRequired(Long userId) {
    return !findPending(userId).isEmpty();
  }

  public List<PendingReconsent> findPending(Long userId) {
    List<PendingReconsent> pending = new ArrayList<>();
    List<TermsVersionView> candidates = termsCatalogService.findReconsentCandidates();
    if (candidates.isEmpty() || isSignupPendingWithoutAgreement(userId)) {
      return pending;
    }
    for (TermsVersionView candidate : candidates) {
      Optional<TermsAgreement> latest =
          termsAgreementRepository.findFirstByUserIdAndTermsCodeOrderByIdDesc(
              userId, candidate.termsCode());
      if (latest.isPresent()
          && (latest.get().getTermsVersion().equals(candidate.version())
              || !latest.get().isAgreed())) {
        continue;
      }
      String previousVersion = latest.map(TermsAgreement::getTermsVersion).orElse(null);
      String previousUrl =
          latest
              .flatMap(
                  agreement ->
                      termsCatalogService.findVersion(
                          agreement.getTermsCode(), agreement.getTermsVersion()))
              .map(TermsVersionView::documentUrl)
              .orElse(null);
      pending.add(new PendingReconsent(candidate, previousVersion, previousUrl));
    }
    return pending;
  }

  private boolean isSignupPendingWithoutAgreement(Long userId) {
    return appUserRepository.findById(userId).filter(user -> !user.isSignupCompleted()).isPresent()
        && !termsAgreementRepository.existsByUserId(userId);
  }

  public record PendingReconsent(
      TermsVersionView current, String previousVersion, String previousDocumentUrl) {}
}
