package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "탈퇴 안내와 보관 항목 미리보기")
public record WithdrawalPreviewResponse(
    boolean isOwner,
    boolean isLastOwner,
    boolean kakaoLinked,
    List<String> destroyedItems,
    List<RetainedItem> retainedItems,
    String displayRuleNotice) {

  @Schema(description = "탈퇴 후 보관 항목")
  public record RetainedItem(
      String itemCode,
      String itemName,
      String reason,
      List<String> reviewTargetLaws,
      int retentionDays,
      String anchorEvent,
      String ruleStatus) {}
}
