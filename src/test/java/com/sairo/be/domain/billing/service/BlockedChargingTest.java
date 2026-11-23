package com.sairo.be.domain.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sairo.be.domain.billing.entity.SubscriptionStatus;
import com.sairo.be.domain.billing.repository.BillingChargeAttemptRepository;
import com.sairo.be.domain.billing.repository.BillingCustomerRepository;
import com.sairo.be.domain.billing.repository.BillingInvoiceRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository;
import com.sairo.be.domain.billing.repository.BillingQueryRepository.PriceScheduleRow;
import com.sairo.be.domain.billing.repository.PriceChangeNoticeRepository;
import com.sairo.be.domain.billing.repository.SubscriptionCancellationRepository;
import com.sairo.be.domain.billing.repository.SubscriptionRepository;
import com.sairo.be.domain.legal.service.TermsCatalogService;
import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.RequestRateLimiter;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.global.support.TokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

class BlockedChargingTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);
  private static final Instant NOW = CLOCK.instant();

  private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
  private final BillingInvoiceRepository invoiceRepository = mock(BillingInvoiceRepository.class);
  private final BillingCustomerRepository customerRepository =
      mock(BillingCustomerRepository.class);
  private final BillingChargeAttemptRepository attemptRepository =
      mock(BillingChargeAttemptRepository.class);
  private final SubscriptionCancellationRepository cancellationRepository =
      mock(SubscriptionCancellationRepository.class);
  private final PriceChangeNoticeRepository noticeRepository =
      mock(PriceChangeNoticeRepository.class);
  private final BillingQueryRepository queryRepository = mock(BillingQueryRepository.class);
  private final PaymentGateway gateway = mock(PaymentGateway.class);
  private final SystemJobRunService jobRunService = mock(SystemJobRunService.class);
  private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
  private final AdvisoryLock advisoryLock = mock(AdvisoryLock.class);
  private final BillingChargeService chargeService = mock(BillingChargeService.class);
  private final InvoiceIssuer invoiceIssuer = mock(InvoiceIssuer.class);
  private final ConsentChecker consentChecker = mock(ConsentChecker.class);

  private static BillingProperties properties(boolean started) {
    return new BillingProperties(1000, Duration.ofMinutes(15), started, "", "");
  }

  private static LiveChargingGuard liveGuard(boolean started) {
    return new LiveChargingGuard(properties(started), "live_sk_example", CLOCK);
  }

  private BillingJobService jobService(LiveChargingGuard guard) {
    return new BillingJobService(
        subscriptionRepository,
        invoiceRepository,
        customerRepository,
        cancellationRepository,
        noticeRepository,
        invoiceIssuer,
        consentChecker,
        mock(ConsentWithdrawals.class),
        chargeService,
        jobRunService,
        advisoryLock,
        transactionTemplate,
        mock(AuthSuspensionService.class),
        mock(BillingNoticeDispatcher.class),
        mock(BillingReconciliationService.class),
        guard,
        mock(LateApprovalRefunds.class));
  }

  private void assertNoRepositoryOrGatewayUse() {
    verifyNoInteractions(
        subscriptionRepository,
        invoiceRepository,
        customerRepository,
        cancellationRepository,
        noticeRepository,
        attemptRepository,
        queryRepository,
        gateway,
        jobRunService,
        chargeService,
        transactionTemplate,
        advisoryLock);
  }

  @Test
  void scheduledChargeJobsDoNothingWhileBlocked() {
    BillingJobService service = jobService(liveGuard(false));

    service.runFirstCharges(NOW);
    service.runRenewals(NOW);
    service.runRetries(NOW);
    service.runDelinquency(NOW);
    service.runPrePaymentExpiry(NOW);

    assertNoRepositoryOrGatewayUse();
  }

  @Test
  void delinquencyAndPrePaymentExpiryQueryTargetsOnceSwitchIsOn() {
    BillingJobService service = jobService(liveGuard(true));

    service.runDelinquency(NOW);
    service.runPrePaymentExpiry(NOW);

    verify(subscriptionRepository)
        .findByStatusIn(
            java.util.List.of(
                SubscriptionStatus.PAST_DUE_GRACE, SubscriptionStatus.PAST_DUE_RESTRICTED));
    verify(subscriptionRepository)
        .findByStatusAndPrePaymentSinceLessThanEqual(
            SubscriptionStatus.PRE_PAYMENT, NOW.minus(BillingPeriods.PRE_PAYMENT_LIMIT));
  }

  @Test
  void rerunWorkIsEmptyWhileBlocked() {
    BillingJobService service = jobService(liveGuard(false));

    assertThat(service.rerunWork("billing-renewal", "renewal:1:100", NOW)).isEmpty();
    assertNoRepositoryOrGatewayUse();
  }

  @Test
  void scheduledChargeJobsQueryTargetsOnceSwitchIsOn() {
    BillingJobService service = jobService(liveGuard(true));

    service.runFirstCharges(NOW);

    verify(subscriptionRepository)
        .findByStatusAndFirstBillingScheduledAtLessThanEqual(SubscriptionStatus.TRIAL, NOW);
  }

  @Test
  void chargeThrowsBeforeTouchingGatewayWhileBlocked() {
    BillingChargeService service =
        new BillingChargeService(
            invoiceRepository,
            attemptRepository,
            subscriptionRepository,
            customerRepository,
            queryRepository,
            gateway,
            mock(TextCipher.class),
            mock(TokenGenerator.class),
            advisoryLock,
            mock(SubscriptionTransitions.class),
            transactionTemplate,
            mock(BillingNoticeDispatcher.class),
            liveGuard(false),
            mock(LateApprovalRefunds.class),
            CLOCK);

    assertThatThrownBy(() -> service.charge(1L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertNoRepositoryOrGatewayUse();
  }

  @Test
  void directPaymentsThrowWhileBlocked() {
    DirectPaymentService service =
        new DirectPaymentService(
            mock(BillingContextService.class),
            subscriptionRepository,
            invoiceRepository,
            customerRepository,
            queryRepository,
            invoiceIssuer,
            consentChecker,
            chargeService,
            liveGuard(false),
            advisoryLock,
            transactionTemplate,
            CLOCK);
    StaffPrincipal principal = mock(StaffPrincipal.class);

    assertThatThrownBy(() -> service.firstPayment(principal))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertThatThrownBy(() -> service.retryPayment(principal))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    assertNoRepositoryOrGatewayUse();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void pricingProvisionalFlagMatchesChargingSwitch(boolean started) {
    BillingProperties properties = properties(started);
    when(queryRepository.findStandardPriceAt(NOW))
        .thenReturn(Optional.of(new PriceScheduleRow(1L, "v1", 1000L, 100L, 50L, NOW, "STANDARD")));
    PublicPricingService service =
        new PublicPricingService(
            queryRepository,
            mock(TermsCatalogService.class),
            mock(RequestRateLimiter.class),
            properties,
            CLOCK);
    LiveChargingGuard guard = new LiveChargingGuard(properties, "live_sk_example", CLOCK);

    assertThat(service.currentPricing("127.0.0.1").provisionalPrice()).isEqualTo(!started);
    assertThat(guard.canCharge()).isEqualTo(started);
  }
}
