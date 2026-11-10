package com.sairo.be.domain.legal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sairo.be.domain.legal.dto.response.PublicCompanyInfoResponse;
import com.sairo.be.domain.legal.repository.CompanyServiceInfoRepository;
import com.sairo.be.domain.legal.repository.PolicyDocumentRepository;
import com.sairo.be.global.config.CompanyProperties;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.RequestRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicCompanyInfoServiceTest {

  private final CompanyServiceInfoRepository infoRepository =
      mock(CompanyServiceInfoRepository.class);
  private final PolicyDocumentRepository policyRepository = mock(PolicyDocumentRepository.class);
  private final RequestRateLimiter rateLimiter = mock(RequestRateLimiter.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);

  private PublicCompanyInfoService service(CompanyProperties properties) {
    when(infoRepository.findLatestPublished(any())).thenReturn(Optional.empty());
    when(policyRepository.findPublishedInForce(any(), any())).thenReturn(List.of());
    return new PublicCompanyInfoService(
        infoRepository, policyRepository, properties, rateLimiter, clock);
  }

  @Test
  void 필수_사업자_정보가_하나라도_비면_게시되지_않은_것으로_본다() {
    CompanyProperties blankName =
        new CompanyProperties("", "김태환", "704-16-02872", "주소", null, null, null, null, null);
    PublicCompanyInfoService service = service(blankName);

    assertThatThrownBy(() -> service.current("127.0.0.1"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMPANY_INFO_NOT_PUBLISHED));
  }

  @Test
  void 선택_항목이_공백이면_응답에서_뺀다() {
    CompanyProperties properties =
        new CompanyProperties(
            "사이로(SAIRO)", "김태환", "704-16-02872", "주소", " ", "", "help@example.com", null, null);

    PublicCompanyInfoResponse response = service(properties).current("127.0.0.1");

    assertThat(response.contactPhone()).isNull();
    assertThat(response.ecommerceNotice()).isNull();
    assertThat(response.supportEmail()).isEqualTo("help@example.com");
  }
}
