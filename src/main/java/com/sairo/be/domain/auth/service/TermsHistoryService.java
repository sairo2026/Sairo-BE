package com.sairo.be.domain.auth.service;

import com.sairo.be.domain.auth.dto.response.TermsAgreementHistoryItemResponse;
import com.sairo.be.domain.auth.entity.ConsentPath;
import com.sairo.be.domain.auth.entity.TermsAgreement;
import com.sairo.be.domain.auth.repository.TermsAgreementRepository;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import com.sairo.be.global.support.TimeRules;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
@Transactional(readOnly = true)
public class TermsHistoryService {

  private static final List<ConsentPath> EXCLUDED =
      List.of(ConsentPath.PURCHASE_CONSENT, ConsentPath.CARD_REGISTRATION);

  private final TermsAgreementRepository termsAgreementRepository;
  private final TermsCatalogService termsCatalogService;

  public TermsHistoryService(
      TermsAgreementRepository termsAgreementRepository, TermsCatalogService termsCatalogService) {
    this.termsAgreementRepository = termsAgreementRepository;
    this.termsCatalogService = termsCatalogService;
  }

  public PageResponse<TermsAgreementHistoryItemResponse> list(
      Long userId, String termsCode, PageQuery pageQuery) {
    var pageable =
        pageQuery.toPageable(Sort.by(Sort.Order.desc("recordedAt"), Sort.Order.desc("id")));
    Page<TermsAgreement> page =
        termsCode == null || termsCode.isBlank()
            ? termsAgreementRepository.findByUserIdAndConsentPathNotIn(userId, EXCLUDED, pageable)
            : termsAgreementRepository.findByUserIdAndTermsCodeAndConsentPathNotIn(
                userId, termsCode, EXCLUDED, pageable);
    return PageResponse.from(
        page,
        agreement ->
            new TermsAgreementHistoryItemResponse(
                agreement.getId(),
                agreement.getTermsCode(),
                termsCatalogService
                    .findVersion(agreement.getTermsCode(), agreement.getTermsVersion())
                    .map(TermsVersionView::title)
                    .orElse(agreement.getTermsCode()),
                agreement.getTermsVersion(),
                agreement.isAgreed(),
                agreement.getConsentPath().name(),
                TimeRules.toDisplay(agreement.getRecordedAt()).toOffsetDateTime()));
  }
}
