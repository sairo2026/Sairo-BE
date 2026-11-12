package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 약관 원문 해시 점검·보관 기간 규칙·카카오 연결 끊기 사건 응답이다(OPS-18·19). */
public final class OpsLegalOpsResponses {

  private OpsLegalOpsResponses() {}

  @Schema(description = "약관 항목별 마지막 해시 점검 결과")
  public record TermsHashCheckItem(
      String termsCode,
      String version,
      @Schema(description = "ACTIVE, SCHEDULED") String status,
      OffsetDateTime effectiveAt,
      OffsetDateTime lastCheckedAt,
      @Schema(description = "MATCH, MISMATCH, ERROR. 점검 전이면 null") String lastResult) {}

  @Schema(description = "약관 해시 점검 결과 목록")
  public record TermsHashCheckListResponse(List<TermsHashCheckItem> items) {}

  @Schema(description = "해시 점검 즉시 실행의 항목별 결과")
  public record TermsHashCheckRunItem(
      String termsCode,
      String version,
      String result,
      @Schema(description = "등록한 해시의 앞 8자리") String expectedHashPrefix,
      @Schema(description = "다시 계산한 해시의 앞 8자리. 읽기 오류면 null") String actualHashPrefix) {}

  @Schema(description = "해시 점검 즉시 실행 결과")
  public record TermsHashCheckRunResponse(
      OffsetDateTime checkedAt,
      int mismatchCount,
      int errorCount,
      List<TermsHashCheckRunItem> items) {}

  @Schema(description = "보관 기간 규칙")
  public record RetentionRuleResponse(
      String ruleCode,
      String dataGroupLabel,
      int retentionDays,
      String anchorEvent,
      String basisNote,
      String status,
      LocalDate effectiveFrom,
      @Schema(description = "낙관적 잠금 버전") long version) {}

  @Schema(description = "보관 기간 규칙 목록")
  public record RetentionRuleListResponse(List<RetentionRuleResponse> items) {}

  @Schema(description = "카카오 연결 끊기 사건. 식별자 원문과 암호문은 돌려주지 않는다")
  public record KakaoUnlinkEventItem(
      Long eventId,
      String direction,
      String status,
      int attemptCount,
      OffsetDateTime nextAttemptAt,
      String lastErrorCode,
      String referrerType,
      Long userId,
      OffsetDateTime occurredAt,
      OffsetDateTime completedAt) {}

  @Schema(description = "카카오 연결 끊기 사건 목록")
  public record KakaoUnlinkEventListResponse(
      List<KakaoUnlinkEventItem> items, int page, int size, long totalElements) {}

  @Schema(description = "수동 재시도 결과")
  public record KakaoUnlinkEventRetryResponse(
      Long eventId, String status, int attemptCount, OffsetDateTime nextAttemptAt) {}
}
