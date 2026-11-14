package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.service.VisitFeasibility.Context;
import com.sairo.be.domain.visit.service.VisitRequestChecker.Slot;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class ScheduleSlotCalculator {

  static final int GRID_MINUTES = 5;
  static final int MAX_EVALUATIONS = 4000;

  public List<Slot> applicable(
      Context context, int durationMinutes, Instant notBefore, Instant now) {
    Duration visit = Duration.ofMinutes(durationMinutes);
    List<Slot> result = new ArrayList<>();
    Instant rangeStart = null;
    Instant lastStart = null;
    int evaluations = 0;
    for (Slot slot : mergedSlots(context.customerSlots())) {
      Instant start = ceil(slot.startsAt().isAfter(notBefore) ? slot.startsAt() : notBefore);
      while (!start.plus(visit).isAfter(slot.endsAt()) && evaluations < MAX_EVALUATIONS) {
        evaluations++;
        boolean feasible =
            VisitFeasibility.violated(context, start, start.plus(visit), now, true).isEmpty();
        if (feasible) {
          if (rangeStart != null
              && start.equals(lastStart.plus(GRID_MINUTES, ChronoUnit.MINUTES))) {
            lastStart = start;
          } else {
            if (rangeStart != null) {
              result.add(new Slot(rangeStart, lastStart.plus(visit)));
            }
            rangeStart = start;
            lastStart = start;
          }
        }
        start = start.plus(GRID_MINUTES, ChronoUnit.MINUTES);
      }
    }
    if (rangeStart != null) {
      result.add(new Slot(rangeStart, lastStart.plus(visit)));
    }
    return result;
  }

  public Optional<Instant> earliestStart(
      Context context, int durationMinutes, Instant notBefore, Instant now) {
    Duration visit = Duration.ofMinutes(durationMinutes);
    int evaluations = 0;
    for (Slot slot : mergedSlots(context.customerSlots())) {
      Instant start = ceil(slot.startsAt().isAfter(notBefore) ? slot.startsAt() : notBefore);
      while (!start.plus(visit).isAfter(slot.endsAt()) && evaluations < MAX_EVALUATIONS) {
        evaluations++;
        if (VisitFeasibility.violated(context, start, start.plus(visit), now, true).isEmpty()) {
          return Optional.of(start);
        }
        start = start.plus(GRID_MINUTES, ChronoUnit.MINUTES);
      }
    }
    return Optional.empty();
  }

  static Instant ceil(Instant value) {
    long step = GRID_MINUTES * 60L;
    long seconds = value.getEpochSecond();
    boolean exact = value.getNano() == 0 && seconds % step == 0;
    long rounded = exact ? seconds : (Math.floorDiv(seconds, step) + 1) * step;
    return Instant.ofEpochSecond(rounded);
  }

  static List<Slot> mergedSlots(List<Slot> slots) {
    List<Slot> sorted = new ArrayList<>(slots);
    sorted.sort(Comparator.comparing(Slot::startsAt));
    List<Slot> merged = new ArrayList<>();
    for (Slot slot : sorted) {
      if (!merged.isEmpty() && !slot.startsAt().isAfter(merged.get(merged.size() - 1).endsAt())) {
        Slot last = merged.remove(merged.size() - 1);
        merged.add(
            new Slot(
                last.startsAt(),
                slot.endsAt().isAfter(last.endsAt()) ? slot.endsAt() : last.endsAt()));
      } else {
        merged.add(slot);
      }
    }
    return merged;
  }
}
