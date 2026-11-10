package com.sairo.be.domain.billing.service;

import com.sairo.be.domain.billing.dto.response.PublicPricingResponse;
import com.sairo.be.domain.billing.mapper.BillingMapper;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.TrialEventRow;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.domain.legal.service.TermsVersionView;
import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.RequestRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class PublicPricingService {

  private static final String TERMS_CODE = "TOS";
  private static final String PRIVACY_CODE = "PRIVACY";
  private static final String REFUND_CODE = "REFUND_POLICY";

  private final BillingQueryRepository queryRepository;
  private final TermsCatalogService termsCatalogService;
  private final RequestRateLimiter rateLimiter;
  private final BillingProperties properties;
  private final Clock clock;

  public PublicPricingService(
      BillingQueryRepository queryRepository,
      TermsCatalogService termsCatalogService,
      RequestRateLimiter rateLimiter,
      BillingProperties properties,
      Clock clock) {
    this.queryRepository = queryRepository;
    this.termsCatalogService = termsCatalogService;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PublicPricingResponse currentPricing(String clientIp) {
    rateLimiter.hit(BillingRateLimits.PUBLIC_PRICING_IP, clientIp);
    Instant now = clock.instant();
    PriceScheduleRow price =
        queryRepository
            .findStandardPriceAt(now)
            .orElseThrow(() -> new BusinessException(ErrorCode.PRICE_SCHEDULE_NOT_PUBLISHED));
    Optional<TrialEventRow> trial = queryRepository.findActiveGeneralTrialEvent(now);
    Optional<TermsVersionView> refund = termsCatalogService.findInForceByCode(REFUND_CODE);
    return BillingMapper.toPublicPricing(
        price,
        trial.orElse(null),
        refund.map(TermsVersionView::version).orElse(null),
        urlOf(TERMS_CODE, "/legal/terms"),
        refund.map(TermsVersionView::documentUrl).orElse("/legal/refund-policy"),
        urlOf(PRIVACY_CODE, "/legal/privacy"),
        properties.pricingImageUrl(),
        !properties.chargingStarted());
  }

  private String urlOf(String termsCode, String fallback) {
    return termsCatalogService
        .findInForceByCode(termsCode)
        .map(TermsVersionView::documentUrl)
        .orElse(fallback);
  }
}
