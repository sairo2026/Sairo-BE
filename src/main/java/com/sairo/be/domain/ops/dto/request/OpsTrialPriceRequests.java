package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;

/** 요금표·체험 이벤트·영업일 달력 요청 본문이다(OPS-08). */
public final class OpsTrialPriceRequests {

  private OpsTrialPriceRequests() {}

  static final long AMOUNT_MAX = 100_000_000L;

  @Schema(description = "요금표 새 버전. 버전 문자열은 서버가 효력 시각의 KST 연월과 대상으로 정한다")
  public record PriceScheduleCreateRequest(
      @Schema(description = "1인 VAT 포함 월액 원화") @NotNull @PositiveOrZero @Max(AMOUNT_MAX) Long soloAmount,
      @Schema(description = "2~4인 구간 인당 추가 월액 원화") @NotNull @PositiveOrZero @Max(AMOUNT_MAX) Long teamFirstExtra,
      @Schema(description = "5인 이상 구간 인당 추가 월액 원화") @NotNull @PositiveOrZero @Max(AMOUNT_MAX) Long teamAfterFourExtra,
      @Schema(description = "시행 시각") @NotNull OffsetDateTime effectiveAt,
      @Schema(description = "공지 시각. 시행 30일 전 이전이어야 한다") @NotNull OffsetDateTime noticeAt,
      @Schema(description = "STANDARD 또는 START_CUSTOMER. 생략하면 STANDARD") String audience) {}

  @Schema(description = "체험 이벤트 생성·승인 요청")
  public record TrialEventCreateRequest(
      @Schema(description = "운영자 화면의 이벤트명") @PlainText(max = 100) String name,
      @Schema(description = "자격 신청창 시작 포함 시각") @NotNull OffsetDateTime startsAt,
      @Schema(description = "자격 신청창 종료 제외 시각") @NotNull OffsetDateTime endsAt,
      @Schema(description = "기간 내 사무소 생성 요구 여부. 생략하면 참") Boolean officeCreatedDuring,
      @Schema(description = "기간 내 최초 인증 신청 요구 여부. 생략하면 참") Boolean firstApplicationDuring,
      @Schema(description = "DURATION 또는 FIXED_END. 생략하면 DURATION") String freeEndMode,
      @Schema(description = "고정 무료 종료 시각. FIXED_END이면 필수") OffsetDateTime fixedFreeEndsAt,
      @Schema(description = "GENERAL 또는 START_CUSTOMER. 생략하면 GENERAL") String audience,
      @Schema(description = "시작 고객 이벤트의 승인 사무소 상한") @Min(1) @Max(10_000) Integer maxOffices) {}

  @Schema(description = "영업일·휴무일 반영")
  public record BusinessCalendarUpdateRequest(
      @Schema(description = "고객 지원 업무일 여부") @NotNull Boolean isBusinessDay,
      @Schema(description = "휴무 또는 임시 영업 사유") @PlainText(min = 0, max = 200) String reason) {}
}
