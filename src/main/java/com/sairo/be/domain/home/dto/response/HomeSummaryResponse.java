package com.sairo.be.domain.home.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "홈 대시보드 요약이다(HomeSummaryResponse).")
public record HomeSummaryResponse(
    List<TodayVisit> todayScheduledVisits,
    List<FollowupTask> openFollowupTasks,
    long activeCoordinationCount,
    List<OpenTask> openTasks,
    long unreadNotificationCount,
    long unreadVisitRequestNoticeCount,
    VisitRequestSummary visitRequestSummary,
    List<StatusCount> coordinationStatusCounts,
    long leaseExpiringWithin90DaysCount,
    List<CalendarDay> calendarDays) {

  public record TodayVisit(
      Long scheduleItemId,
      Long coordinationId,
      OffsetDateTime scheduledStartAt,
      String propertyAddress,
      String customerName) {}

  public record FollowupTask(Long taskId, String title, LocalDate dueDate) {}

  public record OpenTask(
      Long taskId,
      String title,
      LocalDate dueDate,
      String status,
      Long assigneeMembershipId,
      String assigneeName,
      boolean overdue) {}

  public record VisitRequestSummary(
      long pendingCount, long revalidationFailedCount, long timeClosedCount) {}

  public record StatusCount(String status, long count) {}

  public record CalendarDay(LocalDate date, long visitCount, long taskCount) {}
}
