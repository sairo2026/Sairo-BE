package com.sairo.be.domain.contract.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "계약 후속업무 제출 처리 결과(ContractFollowupTaskResponse)다. version은 부모 계약의 현재 버전이다.")
public record ContractFollowupTaskResponse(
    Long taskId,
    boolean submitted,
    @Schema(nullable = true) OffsetDateTime submittedAt,
    Long officeId,
    long version) {

  @Schema(description = "서류·신고 후속업무 목록(ContractFollowupTaskListResponse)이다.")
  public record FollowupList(List<FollowupItem> items) {}

  @Schema(description = "후속업무 한 건이다.")
  public record FollowupItem(
      Long taskId,
      @Schema(allowableValues = {"D90_FOLLOWUP"}) String taskType,
      @Schema(nullable = true) String reportTarget,
      @Schema(nullable = true) String reportBasis,
      @Schema(nullable = true, description = "신고 기한. D-90 후속업무는 임대 만기일") LocalDate targetDate,
      boolean submitted,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      Long officeId,
      long version) {}
}
