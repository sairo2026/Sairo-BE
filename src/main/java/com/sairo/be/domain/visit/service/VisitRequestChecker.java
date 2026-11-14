package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Violation;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Visit;
import com.sairo.be.domain.visit.service.ConditionSpec.RuleItem;
import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class VisitRequestChecker {

  public static final String CUSTOMER_CONFLICT = "CUSTOMER_CONFLICT";
  public static final String PROPERTY_CONFLICT = "PROPERTY_CONFLICT";
  public static final String NO_PERMISSION = "NO_PERMISSION";
  public static final String MIN_LEAD = "MIN_LEAD";

  private static final Long NEW_VISIT_ID = -1L;

  public record Slot(Instant startsAt, Instant endsAt) {}

  public record Subject(
      Instant start,
      Instant end,
      Long coordinationId,
      Long membershipId,
      Instant approvalValidFrom,
      Instant approvalValidUntil) {}

  private VisitRequestChecker() {}

  public static List<String> violatedRules(
      ConditionSpec spec,
      Subject subject,
      List<Visit> confirmedVisits,
      List<Slot> customerSlots,
      Instant now) {
    Set<String> rules = new LinkedHashSet<>();
    if (subject.start().isBefore(subject.approvalValidFrom())
        || subject.end().isAfter(subject.approvalValidUntil())) {
      rules.add(NO_PERMISSION);
    }
    if (!insideCustomerSlots(customerSlots, subject.start(), subject.end())) {
      rules.add(CUSTOMER_CONFLICT);
    }
    Integer lead =
        ApproverConditionEvaluator.effective(
            spec, TimeRules.displayDate(subject.start()), RuleItem.MIN_LEAD);
    if (lead != null && subject.start().isBefore(now.plus(Duration.ofMinutes(lead)))) {
      rules.add(MIN_LEAD);
    }
    for (Visit visit : confirmedVisits) {
      if (TimeRules.overlaps(subject.start(), subject.end(), visit.startAt(), visit.endAt())) {
        rules.add(PROPERTY_CONFLICT);
        break;
      }
    }
    rules.addAll(conditionRules(spec, subject, confirmedVisits, now));
    return new ArrayList<>(rules);
  }

  private static List<String> conditionRules(
      ConditionSpec spec, Subject subject, List<Visit> confirmedVisits, Instant now) {
    Set<String> baseline = new HashSet<>();
    for (Violation violation : ApproverConditionEvaluator.evaluate(spec, confirmedVisits, now)) {
      for (String rule : violation.rules()) {
        baseline.add(violation.visit().scheduleItemId() + ":" + rule);
      }
    }
    List<Visit> combined = new ArrayList<>(confirmedVisits);
    combined.add(
        new Visit(
            NEW_VISIT_ID,
            subject.coordinationId(),
            subject.start(),
            subject.end(),
            "SCHEDULED",
            NEW_VISIT_ID,
            subject.membershipId()));
    Set<String> found = new LinkedHashSet<>();
    for (Violation violation : ApproverConditionEvaluator.evaluate(spec, combined, now)) {
      for (String rule : violation.rules()) {
        if (!baseline.contains(violation.visit().scheduleItemId() + ":" + rule)) {
          found.add(rule);
        }
      }
    }
    return new ArrayList<>(found);
  }

  static boolean insideCustomerSlots(List<Slot> slots, Instant start, Instant end) {
    List<Slot> sorted = new ArrayList<>(slots);
    sorted.sort(Comparator.comparing(Slot::startsAt));
    Instant coveredFrom = null;
    Instant coveredUntil = null;
    for (Slot slot : sorted) {
      if (coveredUntil == null || slot.startsAt().isAfter(coveredUntil)) {
        if (coveredFrom != null && !start.isBefore(coveredFrom) && !end.isAfter(coveredUntil)) {
          return true;
        }
        coveredFrom = slot.startsAt();
        coveredUntil = slot.endsAt();
      } else if (slot.endsAt().isAfter(coveredUntil)) {
        coveredUntil = slot.endsAt();
      }
    }
    return coveredFrom != null && !start.isBefore(coveredFrom) && !end.isAfter(coveredUntil);
  }
}
