package com.sairo.be.domain.legal.service;

import com.sairo.be.domain.legal.entity.TermsCatalog;
import com.sairo.be.domain.legal.entity.TermsCatalogId;
import com.sairo.be.domain.legal.entity.TermsDisplayContext;
import com.sairo.be.domain.legal.repository.TermsCatalogRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class TermsCatalogService {

  private final TermsCatalogRepository termsCatalogRepository;
  private final Clock clock;

  public TermsCatalogService(TermsCatalogRepository termsCatalogRepository, Clock clock) {
    this.termsCatalogRepository = termsCatalogRepository;
    this.clock = clock;
  }

  public List<TermsVersionView> findInForce(TermsDisplayContext context) {
    return termsCatalogRepository.findInForce(context, clock.instant()).stream()
        .map(TermsVersionView::from)
        .toList();
  }

  public Optional<TermsVersionView> findInForceByCode(String termsCode) {
    return termsCatalogRepository
        .findInForceByCode(termsCode, clock.instant())
        .map(TermsVersionView::from);
  }

  public Optional<TermsVersionView> findVersion(String termsCode, String version) {
    return termsCatalogRepository
        .findById(new TermsCatalogId(termsCode, version))
        .map(TermsVersionView::from);
  }

  public List<TermsVersionView> findReconsentCandidates() {
    return termsCatalogRepository.findInForce(TermsDisplayContext.SIGNUP, clock.instant()).stream()
        .filter(TermsCatalog::isReconsentRequired)
        .map(TermsVersionView::from)
        .toList();
  }
}
