package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 시작 고객 합의와 가격 변경 안내 응답이다(OPS-21·22). */
public final class OpsPricingResponses {

  private OpsPricingResponses() {}

  @Schema(description = "시작 고객 합의 목록 항목")
  public record AgreementItem(
      Long agreementId,
      Long officeId,
      String officeName,
      String status,
      Long trialEventId,
      Long priceScheduleId,
      String priceVersion,
      OffsetDateTime priceStartsAt,
      OffsetDateTime priceEndsAt,
      LocalDate contractedOn,
      String contractReference,
      Long createdByOperatorId,
      Long approvedByOperatorId,
      OffsetDateTime approvedAt,
      OffsetDateTime ownerAcknowledgedAt,
      long version) {}

  @Schema(description = "이벤트별 승인 현황. 운영 목표 수는 승인 검사에 쓰지 않는다")
  public record Cohort(
      Long trialEventId,
      String eventName,
      long approvedCount,
      Integer maxOffices,
      int targetCount) {}

  @Schema(description = "시작 고객 합의 목록과 이벤트별 승인 현황")
  public record AgreementListResponse(
      List<AgreementItem> items, List<Cohort> cohorts, int page, int size, long totalElements) {}

  @Schema(description = "시작 고객 합의 등록 결과")
  public record AgreementCreateResponse(
      Long agreementId,
      Long officeId,
      String status,
      Long trialEventId,
      Long priceScheduleId,
      String priceVersion,
      OffsetDateTime priceStartsAt,
      OffsetDateTime priceEndsAt,
      LocalDate contractedOn,
      OffsetDateTime createdAt,
      long version) {}

  @Schema(description = "승인 이벤트 현황")
  public record ApprovalCohort(Long trialEventId, long approvedCount, Integer maxOffices) {}

  @Schema(description = "승인 직후 구독 변경 결과")
  public record ApprovalSubscription(
      Long subscriptionId,
      String status,
      OffsetDateTime trialEndsAt,
      OffsetDateTime firstBillingScheduledAt,
      boolean promoted) {}

  @Schema(description = "시작 고객 합의 승인 결과")
  public record AgreementApproveResponse(
      Long agreementId,
      String status,
      OffsetDateTime approvedAt,
      Long approvedByOperatorId,
      ApprovalCohort cohort,
      ApprovalSubscription subscription,
      long version) {}

  @Schema(description = "시작 고객 합의 취소 결과. 이미 부여한 무료 종료 시각은 바꾸지 않는다")
  public record AgreementRevokeResponse(
      Long agreementId,
      String status,
      OffsetDateTime revokedAt,
      Long revokedByOperatorId,
      boolean freeEndsAtKept,
      Long priceChangeNoticeId,
      long version) {}

  @Schema(description = "가격 변경 안내 현황 항목")
  public record NoticeItem(
      Long noticeId,
      Long officeId,
      String officeName,
      Long subscriptionId,
      String changeKind,
      String fromPriceVersion,
      String toPriceVersion,
      OffsetDateTime notifiedAt,
      OffsetDateTime effectiveAt,
      OffsetDateTime responseDueAt,
      String decision,
      OffsetDateTime decidedAt) {}

  @Schema(description = "필터를 적용한 전체 결과의 결정별 건수")
  public record NoticeSummary(
      long pending, long agreed, long declined, long notRequired, long endedNoConsent) {}

  @Schema(description = "가격 변경 안내 현황")
  public record NoticeListResponse(
      List<NoticeItem> items, NoticeSummary summary, int page, int size, long totalElements) {}

  @Schema(description = "건너뛴 사무소와 사유")
  public record SkippedOffice(Long officeId, String reason) {}

  @Schema(description = "가격 변경 안내 생성 결과")
  public record NoticeCreateResponse(
      Long priceScheduleId, int createdCount, int skippedCount, List<SkippedOffice> skipped) {}
}
