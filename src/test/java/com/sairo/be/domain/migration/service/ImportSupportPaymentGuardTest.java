package com.sairo.be.domain.migration.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sairo.be.domain.billing.service.LiveChargingGuard;
import com.sairo.be.domain.migration.repository.ImportMappingItemRepository;
import com.sairo.be.domain.migration.repository.ImportMappingVersionRepository;
import com.sairo.be.domain.migration.repository.ImportRowResultRepository;
import com.sairo.be.domain.migration.repository.ImportSupportJdbcRepository;
import com.sairo.be.domain.migration.repository.ImportSupportLinkRepository;
import com.sairo.be.domain.migration.repository.ImportSupportRequestRepository;
import com.sairo.be.global.config.BillingProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.security.StaffPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ImportSupportPaymentGuardTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void confirmPaymentThrowsBeforeGatewayWhileChargingBlocked() {
    ImportSupportRequestRepository requestRepository = mock(ImportSupportRequestRepository.class);
    ImportGuard importGuard = mock(ImportGuard.class);
    PaymentGateway gateway = mock(PaymentGateway.class);
    LiveChargingGuard guard =
        new LiveChargingGuard(
            new BillingProperties(1000, Duration.ofMinutes(15), false, "", ""),
            "live_sk_example",
            CLOCK);
    ImportSupportService service =
        new ImportSupportService(
            requestRepository,
            mock(ImportSupportLinkRepository.class),
            mock(ImportSupportJdbcRepository.class),
            importGuard,
            mock(ImportMappingVersionRepository.class),
            mock(ImportMappingItemRepository.class),
            mock(ImportRowResultRepository.class),
            mock(ImportPreviewService.class),
            gateway,
            guard,
            CLOCK);

    assertThatThrownBy(() -> service.confirmPayment(mock(StaffPrincipal.class), 1L, null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.INVALID_TRANSITION);
    verifyNoInteractions(gateway, requestRepository, importGuard);
  }
}
