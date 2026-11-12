package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 시스템 작업·장애·연동·개인정보 사고 응답이다(OPS-09). */
public final class OpsSystemResponses {

  private OpsSystemResponses() {}

  @Schema(description = "시스템 작업 실행 이력 항목")
  public record SystemJobItem(
      Long runId,
      String jobName,
      @Schema(description = "CHARGE_RETRY, ANNOUNCEMENT_PUBLISH, INTEGRATION_CHECK, OTHER")
          String jobType,
      @Schema(description = "이 작업을 재실행할 수 있는 운영 역할. 재실행 대상이 아니면 빈 배열") List<String> rerunRoles,
      Long officeId,
      String officeName,
      String status,
      OffsetDateTime scheduledAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      int attemptCount,
      String errorCode,
      OffsetDateTime nextRunAt,
      Long assignedOperatorId,
      String assignedOperatorName) {}

  @Schema(description = "시스템 작업 실행 이력")
  public record SystemJobListResponse(
      List<SystemJobItem> items, int page, int size, long totalElements) {}

  @Schema(description = "재실행 결과. 원래 실행 ID의 시도 회수를 올려 같은 키로 다시 실행한다")
  public record SystemJobRerunResponse(
      Long runId,
      String jobName,
      String status,
      OffsetDateTime startedAt,
      int attemptCount,
      OffsetDateTime nextRunAt) {}

  @Schema(description = "장애 등록 결과")
  public record IncidentCreateResponse(
      Long incidentId,
      String title,
      String status,
      OffsetDateTime detectedAt,
      OffsetDateTime startedAt,
      Long assignedOperatorId) {}

  @Schema(description = "장애 항목")
  public record IncidentItem(
      Long incidentId,
      String title,
      List<String> affectedServices,
      @Schema(description = "전체 사무소면 빈 배열") List<Long> affectedOffices,
      String status,
      OffsetDateTime detectedAt,
      OffsetDateTime startedAt,
      OffsetDateTime recoveredAt,
      OffsetDateTime closedAt,
      Long assignedOperatorId,
      String rootCause,
      String responseActions,
      String customerNotice,
      String preventionTasks,
      String summary) {}

  @Schema(description = "장애 목록")
  public record IncidentListResponse(
      List<IncidentItem> items, int page, int size, long totalElements) {}

  @Schema(description = "연동 설정 항목. 비밀값은 돌려주지 않는다")
  public record IntegrationItem(
      Long integrationId,
      String environment,
      String provider,
      String functionCode,
      boolean enabled,
      String secretReferenceId,
      String maskedConnection,
      String processingRegion,
      String retentionPolicy,
      String contractStatus,
      String consentStatus,
      OffsetDateTime contractNoticeReviewedAt,
      String lastCheckResult,
      OffsetDateTime lastSuccessAt,
      Long assignedOperatorId,
      @Schema(description = "활성화 승인 운영자. 승인 전이면 null") Long approvedByOperatorId) {}

  @Schema(description = "연동 설정 목록")
  public record IntegrationListResponse(
      List<IntegrationItem> items, int page, int size, long totalElements) {}

  @Schema(description = "연동 설정 변경 결과")
  public record IntegrationUpdateResponse(
      Long integrationId,
      String environment,
      String provider,
      String functionCode,
      boolean enabled,
      String maskedConnection,
      String processingRegion,
      String retentionPolicy,
      String contractStatus,
      String consentStatus,
      OffsetDateTime contractNoticeReviewedAt,
      String lastCheckResult,
      OffsetDateTime lastSuccessAt) {}

  @Schema(description = "연동 연결 검사 결과. 공급자 호출이 실패해도 200이다")
  public record IntegrationCheckResponse(
      Long integrationId, String lastCheckResult, OffsetDateTime lastSuccessAt) {}

  @Schema(description = "개인정보 사고 등록 결과")
  public record PrivacyIncidentCreateResponse(
      Long privacyIncidentId,
      Long incidentLogId,
      OffsetDateTime reportDueAt,
      OffsetDateTime noticeDueAt,
      Long assignedOperatorId,
      OffsetDateTime createdAt) {}

  @Schema(description = "개인정보 사고 항목")
  public record PrivacyIncidentItem(
      Long privacyIncidentId,
      Long incidentLogId,
      String affectedScope,
      String reportRequirement,
      String noticeRequirement,
      OffsetDateTime reportDueAt,
      OffsetDateTime noticeDueAt,
      String responseEvidence,
      Long assignedOperatorId,
      OffsetDateTime createdAt) {}

  @Schema(description = "개인정보 사고 목록")
  public record PrivacyIncidentListResponse(
      List<PrivacyIncidentItem> items, int page, int size, long totalElements) {}

  @Schema(description = "개인정보 사고 증빙 등록 결과")
  public record PrivacyIncidentUpdateResponse(Long privacyIncidentId, String responseEvidence) {}
}
