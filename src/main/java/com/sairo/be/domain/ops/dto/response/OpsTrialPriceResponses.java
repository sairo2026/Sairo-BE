package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 요금표·체험 이벤트·영업일 달력 응답이다(OPS-08). */
public final class OpsTrialPriceResponses {

  private OpsTrialPriceResponses() {}

  @Schema(description = "요금표 버전 목록 항목")
  public record PriceScheduleItem(
      Long scheduleId,
      String version,
      long soloAmount,
      long teamFirstExtra,
      long teamAfterFourExtra,
      OffsetDateTime effectiveAt,
      OffsetDateTime noticeAt,
      String status,
      Long approvedByOperatorId,
      OffsetDateTime createdAt,
      String audience,
      Long createdByOperatorId) {}

  @Schema(description = "요금표 버전 목록")
  public record PriceScheduleListResponse(
      List<PriceScheduleItem> items, int page, int size, long totalElements) {}

  @Schema(description = "요금표 등록 결과")
  public record PriceScheduleCreateResponse(
      Long scheduleId,
      String version,
      String status,
      OffsetDateTime effectiveAt,
      OffsetDateTime noticeAt,
      OffsetDateTime createdAt,
      String audience) {}

  @Schema(description = "요금표 승인 결과")
  public record PriceScheduleApproveResponse(
      Long scheduleId, String version, String status, Long approvedByOperatorId) {}

  @Schema(description = "체험 이벤트 목록 항목")
  public record TrialEventItem(
      Long eventId,
      String name,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      boolean officeCreatedDuring,
      boolean firstApplicationDuring,
      String freeEndMode,
      OffsetDateTime fixedFreeEndsAt,
      String status,
      Long approvedByOperatorId,
      OffsetDateTime createdAt,
      String audience,
      Integer maxOffices,
      Long createdByOperatorId) {}

  @Schema(description = "체험 이벤트 목록")
  public record TrialEventListResponse(
      List<TrialEventItem> items, int page, int size, long totalElements) {}

  @Schema(description = "체험 이벤트 등록 결과. 승인 요청 상태인 REVIEW로 만든다")
  public record TrialEventCreateResponse(
      Long eventId,
      String name,
      String status,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      String freeEndMode,
      OffsetDateTime fixedFreeEndsAt,
      OffsetDateTime createdAt,
      String audience,
      Integer maxOffices) {}

  @Schema(description = "중복 수혜 사무소")
  public record DuplicateOffice(Long officeId, String officeName) {}

  @Schema(description = "체험 이벤트 대상 미리보기와 중복 수혜 검사")
  public record TrialEventPreviewResponse(
      Long eventId,
      long targetOfficeCount,
      long duplicateBenefitCount,
      List<DuplicateOffice> duplicateOffices) {}

  @Schema(description = "체험 이벤트 승인 결과")
  public record TrialEventApproveResponse(Long eventId, String status, Long approvedByOperatorId) {}

  @Schema(description = "체험 이벤트 중단 결과. 이미 부여한 체험은 바꾸지 않는다")
  public record TrialEventStopResponse(Long eventId, String status, OffsetDateTime endsAt) {}

  @Schema(description = "영업일 달력 항목")
  public record BusinessCalendarItem(
      LocalDate calendarDate, boolean isBusinessDay, String reason) {}

  @Schema(description = "영업일 달력")
  public record BusinessCalendarResponse(
      List<BusinessCalendarItem> items, int page, int size, long totalElements) {}
}
