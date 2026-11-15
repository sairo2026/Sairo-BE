package com.sairo.be.domain.visit.service;

import com.sairo.be.domain.visit.entity.GroupingPreference;
import com.sairo.be.domain.visit.repository.ApproverSelectionRepository.PendingRow;
import com.sairo.be.domain.visit.service.ApproverConditionEvaluator.Visit;
import com.sairo.be.global.support.TimeRules;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RequestGroupingOrder {

  private static final long NO_SAME_DAY_VISIT = Long.MAX_VALUE;

  private static final Comparator<PendingRow> RECEIPT =
      Comparator.comparing(PendingRow::submittedAt).thenComparing(PendingRow::id);

  private RequestGroupingOrder() {}

  public static List<PendingRow> sort(
      List<PendingRow> rows, GroupingPreference preference, List<Visit> confirmedVisits) {
    List<PendingRow> ordered = new ArrayList<>(rows);
    if (preference == null) {
      ordered.sort(RECEIPT);
      return ordered;
    }
    Comparator<PendingRow> byGap =
        Comparator.comparingLong(row -> gapMinutes(row, confirmedVisits));
    if (preference == GroupingPreference.SPREAD) {
      byGap = byGap.reversed();
    }
    ordered.sort(byGap.thenComparing(RECEIPT));
    return ordered;
  }

  private static long gapMinutes(PendingRow row, List<Visit> confirmedVisits) {
    long nearest = NO_SAME_DAY_VISIT;
    for (Visit visit : confirmedVisits) {
      if (!TimeRules.displayDate(visit.startAt()).equals(TimeRules.displayDate(row.startAt()))) {
        continue;
      }
      nearest = Math.min(nearest, gap(row.startAt(), row.endAt(), visit.startAt(), visit.endAt()));
    }
    return nearest;
  }

  private static long gap(Instant start, Instant end, Instant otherStart, Instant otherEnd) {
    if (!otherEnd.isAfter(start)) {
      return Duration.between(otherEnd, start).toMinutes();
    }
    if (!otherStart.isBefore(end)) {
      return Duration.between(end, otherStart).toMinutes();
    }
    return 0;
  }
}
