package com.sairo.be.domain.billing.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "반환·보관권한 별도 재인증 요청")
public record GrantAuthRequest(
    @NotNull Long grantId,
    @NotNull AuthMethod authMethod,
    @NotBlank @Size(max = 500) String credential) {

  public enum AuthMethod {
    EMAIL,
    KAKAO
  }
}
