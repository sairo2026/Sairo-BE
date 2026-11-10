package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.domain.ops.entity.ReviewItemCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "인증 심사 필수 확인 항목 저장 요청")
public record OfficeRegistrationReviewChecklistRequest(
    @Schema(description = "확인 상태를 바꿀 항목") @NotEmpty @Size(max = 5) List<@Valid @NotNull Item> items) {

  public record Item(
      @Schema(description = "필수 확인 항목 코드") @NotNull ReviewItemCode itemCode,
      @Schema(description = "확인 완료 여부") @NotNull Boolean checked) {}
}
