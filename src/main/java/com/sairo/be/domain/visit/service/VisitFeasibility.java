package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.repository.ScheduleBusyRepository.BusyVisit;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Visit;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Subject;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class VisitFeasibility {

  public static final String BROKER_CONFLICT = "BROKER_CONFLICT";
  public static final String TRAVEL_CONFLICT = "TRAVEL_CONFLICT";
  public static final String DEPUTY_VISIT_LOCKED = "DEPUTY_VISIT_LOCKED";

  public static final List<String> RULE_ORDER =
      List.of(
          ApproverConditionEvaluator.CONDITION_EXPIRED,
          VisitRequestChecker.NO_PERMISSION,
          ApproverConditionEvaluator.WINDOW_MISMATCH,
          ApproverConditionEvaluator.MAX_VISITS_PER_DAY,
          ApproverConditionEvaluator.MIN_GAP,
          VisitRequestChecker.MIN_LEAD,
          ApproverConditionEvaluator.SINGLE_VISIT_MAX,
          VisitRequestChecker.CUSTOMER_CONFLICT,
          BROKER_CONFLICT,
          VisitRequestChecker.PROPERTY_CONFLICT,
          TRAVEL_CONFLICT);

  public enum HitKind {
    MANAGER_OVERLAP,
    CUSTOMER_OVERLAP,
    PROPERTY_OVERLAP
  }

  public record Hit(HitKind kind, String rule, BusyVisit busy) {}

  public record Context(
      ConditionSpec spec,
      Instant approvalFrom,
      Instant approvalUntil,
      List<Slot> customerSlots,
      List<Visit> approvalVisits,
      List<BusyVisit> managerBusy,
      List<BusyVisit> customerBusy,
      List<BusyVisit> propertyBusy,
      Long coordinationId,
      Long membershipId,
      int travelMinutesAfter) {}

  private VisitFeasibility() {}

  public static List<String> violated(
      Context context, Instant start, Instant end, Instant now, boolean includeMinLead) {
    Set<String> rules =
        new LinkedHashSet<>(
            VisitRequestChecker.violatedRules(
                context.spec(),
                new Subject(
                    start,
                    end,
                    context.coordinationId(),
                    context.membershipId(),
                    context.approvalFrom(),
                    context.approvalUntil()),
                context.approvalVisits(),
                context.customerSlots(),
                now));
    if (!includeMinLead) {
      rules.remove(VisitRequestChecker.MIN_LEAD);
    }
    for (Hit hit :
        busyHits(
            start,
            end,
            context.travelMinutesAfter(),
            context.managerBusy(),
            context.customerBusy(),
            context.propertyBusy())) {
      rules.add(hit.rule());
    }
    List<String> ordered = new ArrayList<>(rules);
    ordered.sort(Comparator.comparingInt(RULE_ORDER::indexOf));
    return ordered;
  }

  public static List<Hit> busyHits(
      Instant start,
      Instant end,
      int travelMinutesAfter,
      List<BusyVisit> managerBusy,
      List<BusyVisit> customerBusy,
      List<BusyVisit> propertyBusy) {
    List<Hit> hits = new ArrayList<>();
    Instant ownTravelEnd = end.plus(travelMinutesAfter, ChronoUnit.MINUTES);
    for (BusyVisit busy : managerBusy) {
      if (TimeRules.overlaps(start, end, busy.startAt(), busy.endAt())) {
        hits.add(new Hit(HitKind.MANAGER_OVERLAP, BROKER_CONFLICT, busy));
      } else if (overlapsTravel(start, end, busy)
          || (travelMinutesAfter > 0
              && TimeRules.overlaps(end, ownTravelEnd, busy.startAt(), busy.endAt()))) {
        hits.add(new Hit(HitKind.MANAGER_OVERLAP, TRAVEL_CONFLICT, busy));
      }
    }
    for (BusyVisit busy : customerBusy) {
      if (TimeRules.overlaps(start, end, busy.startAt(), busy.endAt())
          || overlapsTravel(start, end, busy)) {
        hits.add(new Hit(HitKind.CUSTOMER_OVERLAP, VisitRequestChecker.CUSTOMER_CONFLICT, busy));
      }
    }
    for (BusyVisit busy : propertyBusy) {
      if (TimeRules.overlaps(start, end, busy.startAt(), busy.endAt())) {
        hits.add(new Hit(HitKind.PROPERTY_OVERLAP, VisitRequestChecker.PROPERTY_CONFLICT, busy));
      }
    }
    return hits;
  }

  private static boolean overlapsTravel(Instant start, Instant end, BusyVisit busy) {
    if (busy.travelEndsAt() == null) {
      return false;
    }
    Instant travelStart = busy.travelStartsAt() == null ? busy.endAt() : busy.travelStartsAt();
    return TimeRules.overlaps(start, end, travelStart, busy.travelEndsAt());
  }
}
