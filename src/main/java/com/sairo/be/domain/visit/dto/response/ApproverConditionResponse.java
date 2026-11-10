package com.sairo.be.domain.visit.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ApproverConditionResponse(
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
    List<WindowView> normalizedRanges,
    boolean normalizationApplied,
    OffsetDateTime registeredAt,
    OffsetDateTime supersededAt,
    List<DateRuleView> dateRules,
    int dateRuleReplacedCount,
    boolean enteredViaProfile,
    PrivacyNotice privacyNotice) {

  public record WindowView(
      String windowType,
      Integer weekday,
      LocalDate windowDate,
      String startsTime,
      String endsTime,
      boolean isException) {}

  public record LimitView(String kind, Integer value) {}

  public record DateRuleView(
      LocalDate ruleDate,
      LimitView maxVisitsPerDay,
      LimitView minGapMinutes,
      LimitView minLeadMinutes,
      LimitView singleVisitMaxMinutes) {}

  public record PrivacyNotice(String noticeCode, String noticeVersion, int retentionDays) {}
}
