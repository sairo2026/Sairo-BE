package com.sairo.be.domain.visit.dto.response;

import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse.DateRuleView;
import com.sairo.be.domain.visit.dto.response.ApproverConditionResponse.WindowView;
import java.time.OffsetDateTime;
import java.util.List;

public record ApproverConditionHistoryResponse(List<Item> items) {

  public record Item(
      Long conditionId,
      Long approvalId,
      int versionNo,
      String status,
      OffsetDateTime appliesFrom,
      OffsetDateTime appliesUntil,
      Integer maxVisitsPerDay,
      Integer minGapMinutes,
      Integer minLeadMinutes,
      Integer singleVisitMaxMinutes,
      String groupingPreference,
      String source,
      List<WindowView> windows,
      OffsetDateTime registeredAt,
      OffsetDateTime supersededAt,
      List<DateRuleView> dateRules,
      int dateRuleReplacedCount,
      boolean enteredViaProfile) {}
}
