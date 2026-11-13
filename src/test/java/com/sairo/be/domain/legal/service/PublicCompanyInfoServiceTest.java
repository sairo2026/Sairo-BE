package com.sairo.be.domain.legal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sairo.be.domain.legal.dto.response.PublicCompanyInfoResponse;
import com.sairo.be.domain.legal.repository.CompanyServiceInfoRepository;
import com.sairo.be.domain.legal.repository.PolicyDocumentRepository;
import com.sairo.be.global.config.CompanyProperties;
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
  void 필수_사업자_정보가_비어도_오류_없이_그_항목만_null로_준다() {
    CompanyProperties blankName =
        new CompanyProperties("", "김태환", "704-16-02872", "주소", null, null, null, null, null);

    PublicCompanyInfoResponse response = service(blankName).current("127.0.0.1");

    assertThat(response.businessName()).isNull();
    assertThat(response.representativeName()).isEqualTo("김태환");
    assertThat(response.versionNo()).isNull();
    assertThat(response.publishedAt()).isNull();
  }

  @Test
  void 선택_항목이_공백이면_null로_준다() {
    CompanyProperties properties =
        new CompanyProperties(
            "사이로(SAIRO)", "김태환", "704-16-02872", "주소", " ", "", "help@example.com", null, null);

    PublicCompanyInfoResponse response = service(properties).current("127.0.0.1");

    assertThat(response.contactPhone()).isNull();
    assertThat(response.ecommerceNotice()).isNull();
    assertThat(response.supportEmail()).isEqualTo("help@example.com");
  }
}
