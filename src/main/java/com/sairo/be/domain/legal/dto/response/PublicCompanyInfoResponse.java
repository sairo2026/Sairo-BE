package com.sairo.be.domain.legal.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "공개 사이트 하단에 보여줄 사업자 정보. 값이 없는 항목은 응답에서 생략한다.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCompanyInfoResponse(
    Integer versionNo,
    OffsetDateTime publishedAt,
    String businessName,
    String representativeName,
    String businessNumber,
    String address,
    String contactPhone,
    String ecommerceNotice,
    String supportEmail,
    String privacyContact,
    String privacyRequestPath,
    List<PolicyLink> links) {

  @Schema(description = "정책 문서 링크")
  public record PolicyLink(String kind, String title, String url) {}
}
