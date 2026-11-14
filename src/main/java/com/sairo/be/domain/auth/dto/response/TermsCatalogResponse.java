package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "시행 중인 약관 목록 응답")
public record TermsCatalogResponse(
    List<Item> items,
    @Schema(description = "휴대폰 인증번호 본인확인이 필수인지. false이면 번호는 입력만 받고 미인증으로 저장한다")
        boolean phoneVerificationRequired) {

  @Schema(description = "약관 항목")
  public record Item(
      String termsCode,
      String version,
      boolean required,
      String title,
      String documentUrl,
      String category,
      boolean reconsentRequired,
      String changeSummary) {}
}
