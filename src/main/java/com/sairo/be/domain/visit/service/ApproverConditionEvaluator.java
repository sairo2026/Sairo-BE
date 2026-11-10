package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.service.ConditionSpec.DateRule;
import com.sairo.be.domain.visit.service.ConditionSpec.Limit;
import com.sairo.be.domain.visit.service.ConditionSpec.LimitKind;
import com.sairo.be.domain.visit.service.ConditionSpec.RuleItem;
import com.sairo.be.domain.visit.service.ConditionSpec.Window;
import com.sairo.be.domain.visit.service.ConditionSpec.WindowType;
import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ApproverConditionEvaluator {

  public static final String CONDITION_EXPIRED = "CONDITION_EXPIRED";
  public static final String WINDOW_MISMATCH = "WINDOW_MISMATCH";
  public static final String MAX_VISITS_PER_DAY = "MAX_VISITS_PER_DAY";
  public static final String MIN_GAP = "MIN_GAP";
  public static final String SINGLE_VISIT_MAX = "SINGLE_VISIT_MAX";

  public record Visit(
      Long scheduleItemId,
      Long coordinationId,
      Instant startAt,
      Instant endAt,
      String result,
      Long requestId,
      Long applicantMembershipId) {

    boolean isScheduled() {
      return "SCHEDULED".equals(result);
    }
  }

  public record Violation(Visit visit, List<String> rules) {}

  private record Range(int start, int end) {}

  private ApproverConditionEvaluator() {}

  public static List<Violation> evaluate(ConditionSpec spec, List<Visit> visits, Instant now) {
    List<Visit> ordered =
        visits.stream()
            .sorted(Comparator.comparing(Visit::startAt).thenComparing(Visit::scheduleItemId))
            .toList();
    Map<Long, List<String>> found = new HashMap<>();
    Map<LocalDate, Integer> countByDate = new HashMap<>();
    Visit previous = null;
    for (Visit visit : ordered) {
      LocalDate date = TimeRules.displayDate(visit.startAt());
      int ordinal = countByDate.merge(date, 1, Integer::sum);
      List<String> rules = new ArrayList<>();
      if (visit.startAt().isBefore(spec.appliesFrom())
          || !visit.startAt().isBefore(spec.appliesUntil())) {
        rules.add(CONDITION_EXPIRED);
      } else {
        if (!withinWindows(spec, visit)) {
          rules.add(WINDOW_MISMATCH);
        }
        Integer max = effective(spec, date, RuleItem.MAX_VISITS_PER_DAY);
        if (max != null && ordinal > max) {
          rules.add(MAX_VISITS_PER_DAY);
        }
        if (previous != null && gapViolated(spec, previous, visit)) {
          rules.add(MIN_GAP);
        }
        Integer single = effective(spec, date, RuleItem.SINGLE_VISIT_MAX);
        long minutes = Duration.between(visit.startAt(), visit.endAt()).toMinutes();
        if (single != null && minutes > single) {
          rules.add(SINGLE_VISIT_MAX);
        }
      }
      found.put(visit.scheduleItemId(), rules);
      previous = visit;
    }
    List<Violation> violations = new ArrayList<>();
    for (Visit visit : ordered) {
      List<String> rules = found.get(visit.scheduleItemId());
      if (visit.isScheduled() && visit.endAt().isAfter(now) && !rules.isEmpty()) {
        violations.add(new Violation(visit, rules));
      }
    }
    return violations;
  }

  static Integer effective(ConditionSpec spec, LocalDate date, RuleItem item) {
    for (DateRule rule : spec.dateRules()) {
      if (rule.ruleDate().equals(date)) {
        Limit limit = rule.item(item);
        if (limit != null) {
          return limit.kind() == LimitKind.UNLIMITED ? null : limit.value();
        }
      }
    }
    return switch (item) {
      case MAX_VISITS_PER_DAY -> spec.maxVisitsPerDay();
      case MIN_GAP -> spec.minGapMinutes();
      case MIN_LEAD -> spec.minLeadMinutes();
      case SINGLE_VISIT_MAX -> spec.singleVisitMaxMinutes();
    };
  }

  private static boolean gapViolated(ConditionSpec spec, Visit previous, Visit next) {
    LocalDate previousDate = TimeRules.displayDate(previous.startAt());
    LocalDate nextDate = TimeRules.displayDate(next.startAt());
    int required = valueOrZero(effective(spec, nextDate, RuleItem.MIN_GAP));
    if (!previousDate.equals(nextDate)) {
      required = Math.max(required, valueOrZero(effective(spec, previousDate, RuleItem.MIN_GAP)));
    }
    if (required == 0) {
      return false;
    }
    return Duration.between(previous.endAt(), next.startAt()).toMinutes() < required;
  }

  private static int valueOrZero(Integer value) {
    return value == null ? 0 : value;
  }

  private static boolean withinWindows(ConditionSpec spec, Visit visit) {
    LocalDate day = TimeRules.displayDate(visit.startAt());
    LocalDate lastDay = TimeRules.displayDate(visit.endAt().minusNanos(1));
    while (!day.isAfter(lastDay)) {
      Instant dayStart = TimeRules.startOfDisplayDay(day);
      Instant dayEnd = TimeRules.startOfDisplayDay(day.plusDays(1));
      Instant segmentStart = visit.startAt().isAfter(dayStart) ? visit.startAt() : dayStart;
      Instant segmentEnd = visit.endAt().isBefore(dayEnd) ? visit.endAt() : dayEnd;
      long startSecond = Duration.between(dayStart, segmentStart).getSeconds();
      long endSecond = Duration.between(dayStart, segmentEnd).getSeconds();
      boolean contained = false;
      for (Range range : allowedRanges(spec, day)) {
        if (range.start() * 60L <= startSecond && endSecond <= range.end() * 60L) {
          contained = true;
          break;
        }
      }
      if (!contained) {
        return false;
      }
      day = day.plusDays(1);
    }
    return true;
  }

  private static List<Range> allowedRanges(ConditionSpec spec, LocalDate date) {
    List<Window> dateRows = new ArrayList<>();
    List<Window> weekdayRows = new ArrayList<>();
    for (Window window : spec.windows()) {
      if (window.type() == WindowType.DATE && date.equals(window.date())) {
        dateRows.add(window);
      } else if (window.type() == WindowType.WEEKDAY
          && window.weekday() == date.getDayOfWeek().getValue()) {
        weekdayRows.add(window);
      }
    }
    List<Window> rows = dateRows.isEmpty() ? weekdayRows : dateRows;
    List<Range> allowed = new ArrayList<>();
    List<Range> excluded = new ArrayList<>();
    for (Window window : rows) {
      (window.exception() ? excluded : allowed)
          .add(new Range(window.startMinute(), window.endMinute()));
    }
    List<Range> result = new ArrayList<>();
    for (Range range : merge(allowed)) {
      List<Range> pieces = new ArrayList<>(List.of(range));
      for (Range cut : merge(excluded)) {
        List<Range> next = new ArrayList<>();
        for (Range piece : pieces) {
          if (cut.end() <= piece.start() || cut.start() >= piece.end()) {
            next.add(piece);
            continue;
          }
          if (cut.start() > piece.start()) {
            next.add(new Range(piece.start(), cut.start()));
          }
          if (cut.end() < piece.end()) {
            next.add(new Range(cut.end(), piece.end()));
          }
        }
        pieces = next;
      }
      result.addAll(pieces);
    }
    return result;
  }

  private static List<Range> merge(List<Range> ranges) {
    List<Range> sorted = new ArrayList<>(ranges);
    sorted.sort(Comparator.comparingInt(Range::start));
    List<Range> merged = new ArrayList<>();
    for (Range range : sorted) {
      if (!merged.isEmpty() && range.start() <= merged.get(merged.size() - 1).end()) {
        Range last = merged.remove(merged.size() - 1);
        merged.add(new Range(last.start(), Math.max(last.end(), range.end())));
      } else {
        merged.add(range);
      }
    }
    return merged;
  }
}
