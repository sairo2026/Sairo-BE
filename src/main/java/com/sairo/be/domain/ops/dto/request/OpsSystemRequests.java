package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** 시스템 작업·장애·연동·개인정보 사고 요청 본문이다(OPS-09). */
public final class OpsSystemRequests {

  private OpsSystemRequests() {}

  @Schema(description = "시스템 작업 재실행. 서버가 실행 ID의 원래 멱등키를 복원한다")
  public record SystemJobRerunRequest(
      @Schema(description = "경로 작업의 기존 실행 ID") @NotNull @Positive Long runId) {}

  @Schema(description = "장애 등록. 상태는 DETECTED, 감지 일시는 서버 시각이다")
  public record IncidentCreateRequest(
      @Schema(description = "장애 제목") @PlainText(max = 200) String title,
      @Schema(description = "영향 서비스 코드 목록") @NotEmpty @Size(max = 20) List<String> affectedServices,
      @Schema(description = "영향 사무소 ID 목록. 전체 사무소면 빈 배열") @Size(max = 100) List<@NotNull @Positive Long> affectedOfficeIds,
      @Schema(description = "장애 시작 추정 시각") @NotNull OffsetDateTime startedAt,
      @Schema(description = "조사로 확인한 장애 원인") @PlainText(min = 0, max = 10_000) String rootCause,
      @Schema(description = "장애 대응 담당 운영자. 활성 운영자") @Positive Long assignedOperatorId) {}

  @Schema(description = "장애 상태 갱신. 상태는 한 단계씩만 바꾸고 같은 상태면 다른 필드만 고친다. null인 필드는 바꾸지 않는다")
  public record IncidentUpdateRequest(
      @Schema(description = "DETECTED, CONFIRMED, RESPONDING, RECOVERED, CLOSED") @NotNull String status,
      @Schema(description = "서비스 복구 확인 시각") OffsetDateTime recoveredAt,
      @Schema(description = "조사로 확인한 장애 원인") @PlainText(min = 0, max = 10_000) String rootCause,
      @Schema(description = "대응·복구 조치 이력") @PlainText(min = 0, max = 10_000) String responseActions,
      @Schema(description = "고객 안내 내용과 공지 식별자") @PlainText(min = 0, max = 10_000)
          String customerNotice,
      @Schema(description = "재발 방지 작업과 책임자") @PlainText(min = 0, max = 10_000)
          String preventionTasks,
      @Schema(description = "장애 요약") @PlainText(min = 0, max = 10_000) String summary,
      @Schema(description = "장애 대응 담당 운영자. 활성 운영자") @Positive Long assignedOperatorId) {}

  @Schema(description = "연동 설정 변경. 비밀값은 받지 않는다. null인 선택 필드는 바꾸지 않는다")
  public record IntegrationUpdateRequest(
      @Schema(description = "현재 환경에서 연동 활성 여부") @NotNull Boolean enabled,
      @Schema(description = "공급자 변경") @PlainText(min = 0, max = 100) String provider,
      @Schema(description = "처리지역 변경") @PlainText(min = 0, max = 100) String processingRegion,
      @Schema(description = "보유기간과 파기 기준") @PlainText(min = 0, max = 2_000) String retentionPolicy,
      @Schema(description = "PENDING, SIGNED, EXPIRED, TERMINATED") String contractStatus,
      @Schema(description = "NOT_REQUIRED, PENDING, GRANTED, WITHDRAWN") String consentStatus,
      @Schema(description = "참이면 서버가 계약·고지 점검 시각을 현재 시각으로 기록") Boolean contractNoticeReviewed) {}

  @Schema(description = "장애와 연결한 개인정보 사고 등록. 담당자는 요청한 운영자다")
  public record PrivacyIncidentCreateRequest(
      @Schema(description = "연결할 장애 사건. 사건당 1건") @NotNull @Positive Long incidentLogId,
      @Schema(description = "개인정보 사고 대상과 범위") @PlainText(max = 5_000) String affectedScope,
      @Schema(description = "신고 요건 판단 내용") @PlainText(max = 5_000) String reportRequirement,
      @Schema(description = "통지 요건 판단 내용") @PlainText(max = 5_000) String noticeRequirement,
      @Schema(description = "해당하는 신고 기한") OffsetDateTime reportDueAt,
      @Schema(description = "해당하는 통지 기한") OffsetDateTime noticeDueAt) {}

  @Schema(description = "개인정보 사고의 신고·통지·조치 증빙 위치. 증빙 원문은 싣지 않는다")
  public record PrivacyIncidentUpdateRequest(
      @Schema(description = "신고·통지·조치 증빙 위치") @PlainText(max = 1_000) String responseEvidence) {}
}
