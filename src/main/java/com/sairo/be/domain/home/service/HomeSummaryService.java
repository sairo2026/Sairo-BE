package com.sairo.be.domain.home.service;

import com.sairo.be.domain.home.dto.response.HomeSummaryResponse;
import com.sairo.be.domain.home.dto.response.HomeSummaryResponse.CalendarDay;
import com.sairo.be.domain.home.dto.response.HomeSummaryResponse.FollowupTask;
import com.sairo.be.domain.home.dto.response.HomeSummaryResponse.OpenTask;
import com.sairo.be.domain.home.dto.response.HomeSummaryResponse.StatusCount;
import com.sairo.be.domain.home.dto.response.HomeSummaryResponse.TodayVisit;
import com.sairo.be.domain.home.dto.response.HomeSummaryResponse.VisitRequestSummary;
import com.sairo.be.domain.home.repository.HomeSummaryRepository;
import com.sairo.be.domain.home.repository.HomeSummaryRepository.DayCount;
import com.sairo.be.domain.home.repository.NotificationRepository;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StaffRole;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 홈 대시보드(HOME-01)다. 중개보조원은 본인이 담당인 조율과 본인 업무만 집계하고 계약 지표는 비운다. */
@Profile("!migrate")
@Service
public class HomeSummaryService {

  /** 조율 상태 9개를 API 명세서 순서대로 모두 돌려준다. */
  static final List<String> COORDINATION_STATUSES =
      List.of(
          "TIME_COLLECTING",
          "COORDINATABLE",
          "FINAL_CONFIRMATION_REQUIRED",
          "APPLYING",
          "CONFIRMED",
          "RESCHEDULE_REQUIRED",
          "VISIT_COMPLETED",
          "VISIT_INCOMPLETE",
          "CANCELLED");

  static final String FOLLOWUP_TITLE = "임대 만기 D-90 후속업무";
  static final int OPEN_TASK_LIMIT = 100;
  static final int LEASE_WINDOW_DAYS = 90;

  private final HomeSummaryRepository repository;
  private final NotificationRepository notificationRepository;
  private final Clock clock;

  public HomeSummaryService(
      HomeSummaryRepository repository,
      NotificationRepository notificationRepository,
      Clock clock) {
    this.repository = repository;
    this.notificationRepository = notificationRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public HomeSummaryResponse summary(StaffPrincipal principal) {
    boolean deputy = principal.role() == StaffRole.DEPUTY;
    Long officeId = principal.officeId();
    Long scope = deputy ? principal.membershipId() : null;
    LocalDate today = TimeRules.displayDate(clock.instant());

    List<TodayVisit> visits =
        repository
            .scheduledVisits(
                officeId,
                scope,
                TimeRules.startOfDisplayDay(today),
                TimeRules.endOfDisplayDayExclusive(today))
            .stream()
            .map(
                row ->
                    new TodayVisit(
                        row.itemId(),
                        row.coordinationId(),
                        TimeRules.toDisplayOffset(row.startAt()),
                        row.propertyAddress() == null || row.propertyAddress().isBlank()
                            ? row.propertyName()
                            : row.propertyAddress(),
                        row.customerName()))
            .toList();

    List<FollowupTask> followups =
        deputy
            ? List.of()
            : repository.openFollowups(officeId).stream()
                .map(row -> new FollowupTask(row.id(), FOLLOWUP_TITLE, row.dueDate()))
                .toList();

    List<OpenTask> openTasks =
        repository.openTasks(officeId, scope, today, deputy, OPEN_TASK_LIMIT).stream()
            .map(
                row ->
                    new OpenTask(
                        row.id(),
                        row.title(),
                        row.dueDate(),
                        row.status(),
                        row.assigneeId(),
                        row.assigneeName(),
                        row.dueDate().isBefore(today)))
            .toList();

    Map<String, Long> counts = repository.coordinationStatusCounts(officeId, scope);
    List<StatusCount> statusCounts =
        COORDINATION_STATUSES.stream()
            .map(status -> new StatusCount(status, counts.getOrDefault(status, 0L)))
            .toList();

    long leaseExpiring =
        deputy
            ? 0
            : repository.leaseExpiringWithin(officeId, today, today.plusDays(LEASE_WINDOW_DAYS));

    HomeSummaryRepository.RequestCounts requests = repository.requestCounts(officeId, scope);

    return new HomeSummaryResponse(
        visits,
        followups,
        repository.activeCoordinations(officeId, scope),
        openTasks,
        notificationRepository.count(principal.userId(), officeId, true),
        repository.unreadVisitRequestNotices(principal.membershipId()),
        new VisitRequestSummary(
            requests.pending(), requests.revalidationFailed(), requests.timeClosed()),
        statusCounts,
        leaseExpiring,
        calendar(officeId, scope, deputy, YearMonth.from(today)));
  }

  private List<CalendarDay> calendar(Long officeId, Long scope, boolean deputy, YearMonth month) {
    LocalDate first = month.atDay(1);
    LocalDate last = month.atEndOfMonth();
    TreeMap<LocalDate, long[]> days = new TreeMap<>();
    for (DayCount visit :
        repository.visitDays(
            officeId,
            scope,
            TimeRules.startOfDisplayDay(first),
            TimeRules.endOfDisplayDayExclusive(last))) {
      days.computeIfAbsent(visit.date(), key -> new long[2])[0] = visit.count();
    }
    for (DayCount task : repository.taskDays(officeId, scope, first, last, deputy)) {
      days.computeIfAbsent(task.date(), key -> new long[2])[1] = task.count();
    }
    List<CalendarDay> result = new ArrayList<>();
    days.forEach((date, values) -> result.add(new CalendarDay(date, values[0], values[1])));
    return result;
  }
}
