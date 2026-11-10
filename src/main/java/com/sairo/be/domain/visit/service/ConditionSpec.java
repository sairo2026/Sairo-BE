package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.GroupingPreference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ConditionSpec(
    Instant appliesFrom,
    Instant appliesUntil,
    Integer maxVisitsPerDay,
    Integer minGapMinutes,
    Integer minLeadMinutes,
    Integer singleVisitMaxMinutes,
    GroupingPreference groupingPreference,
    List<Window> windows,
    List<DateRule> dateRules) {

  public enum WindowType {
    WEEKDAY,
    DATE
  }

  public enum LimitKind {
    VALUE,
    UNLIMITED
  }

  public enum RuleItem {
    MAX_VISITS_PER_DAY,
    MIN_GAP,
    MIN_LEAD,
    SINGLE_VISIT_MAX
  }

  public record Window(
      WindowType type,
      Integer weekday,
      LocalDate date,
      int startMinute,
      int endMinute,
      boolean exception) {}

  public record Limit(LimitKind kind, Integer value) {}

  public record DateRule(
      LocalDate ruleDate,
      Limit maxVisitsPerDay,
      Limit minGap,
      Limit minLead,
      Limit singleVisitMax) {

    public Limit item(RuleItem item) {
      return switch (item) {
        case MAX_VISITS_PER_DAY -> maxVisitsPerDay;
        case MIN_GAP -> minGap;
        case MIN_LEAD -> minLead;
        case SINGLE_VISIT_MAX -> singleVisitMax;
      };
    }
  }
}
