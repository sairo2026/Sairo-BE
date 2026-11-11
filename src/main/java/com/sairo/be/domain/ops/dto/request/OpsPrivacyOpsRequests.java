package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;

/** 개인정보 권리 요청 처리, 반환·보관권한자 변경, 파기 큐 처리 요청 본문이다(OPS-07·13). */
public final class OpsPrivacyOpsRequests {

  private OpsPrivacyOpsRequests() {}

  private static final String STORAGE_RESULT = "SUCCESS|EXCLUDED|FAILED";

  @Schema(description = "개인정보 권리 요청 처리 단계 기록")
  public record PrivacyRequestProcessRequest(
      @Schema(
              description =
                  "FORWARD, REQUEST_COOPERATION, REQUEST_SUPPLEMENT, APPROVE, RESTRICT, EXECUTE,"
                      + " REJECT, COMPLETE")
          @NotNull @Pattern(
              regexp =
                  "FORWARD|REQUEST_COOPERATION|REQUEST_SUPPLEMENT|APPROVE|RESTRICT|EXECUTE|REJECT|COMPLETE")
          String action,
      @Schema(description = "처리 메모. RESTRICT·REJECT이면 필수이며 결정 근거로 요청자에게 안내된다")
          @PlainText(min = 0, max = 2000)
          String note,
      @Schema(description = "실행 결과와 완료 증빙 위치. COMPLETE이면 필수") @PlainText(min = 0, max = 500)
          String evidenceReference,
      @Schema(description = "요청자 본인확인 결과 기록(선택). VERIFIED 또는 FAILED")
          @Pattern(regexp = "VERIFIED|FAILED") String identityStatus) {}

  @Schema(description = "반환권한자 변경")
  public record ReturnGrantTransferRequest(
      @Schema(description = "새 권한자 회원 ID") @NotNull @Positive Long newGranteeUserId,
      @Schema(description = "새 권한자의 본인확인 증빙 참조") @PlainText(max = 200)
          String identityEvidenceReference,
      @Schema(description = "새 권한자와 사무소의 등록관계 확인 자료 참조") @PlainText(max = 200)
          String registrationRelationReference,
      @Schema(description = "권한자 변경 사유") @PlainText(max = 1000) String reason) {}

  @Schema(description = "보관권한자 변경")
  public record ArchiveGrantTransferRequest(
      @Schema(description = "새 권한자 회원 ID") @NotNull @Positive Long newGranteeUserId,
      @Schema(description = "새 권한자의 본인확인 증빙 참조") @PlainText(max = 200)
          String identityEvidenceReference,
      @Schema(description = "새 권한자와 사무소의 등록관계 확인 자료 참조") @PlainText(max = 200)
          String registrationRelationReference,
      @Schema(description = "새 권한자와 체결한 보관계약 참조") @PlainText(max = 200) String contractReference,
      @Schema(description = "권한자 변경 사유") @PlainText(max = 1000) String reason) {}

  @Schema(description = "파기 보존 예외 처리. 승인자는 요청한 운영자와 달라야 한다")
  public record DeletionQueueExcludeRequest(
      @Schema(description = "보존 예외 근거") @PlainText(max = 1000) String exclusionReason,
      @Schema(description = "보존·분쟁 보류 만료 시각") @NotNull OffsetDateTime holdExpiresAt,
      @Schema(description = "제외 재검토 예정일") @NotNull OffsetDateTime exclusionReviewedAt,
      @Schema(description = "승인 운영자(활성 개인정보담당)") @NotNull @Positive Long approvedByOperatorId) {}

  @Schema(description = "파기 결과 검증. 검증자는 실행자와 다른 활성 개인정보담당이어야 한다")
  public record DeletionQueueVerifyRequest(
      @Schema(description = "운영 저장소 결과") @Pattern(regexp = STORAGE_RESULT) String storageResult,
      @Schema(description = "검색 색인 결과") @Pattern(regexp = STORAGE_RESULT) String searchIndexResult,
      @Schema(description = "파일 결과") @Pattern(regexp = STORAGE_RESULT) String fileResult,
      @Schema(description = "임시 내보내기 결과") @Pattern(regexp = STORAGE_RESULT) String temporaryExportResult,
      @Schema(description = "백업 만료 시각(선택)") OffsetDateTime backupExpiresAt,
      @Schema(description = "검증 증빙") @PlainText(max = 1000) String evidence) {}

  @Schema(description = "실행 전 파기 건 취소")
  public record DeletionQueueCancelRequest(
      @Schema(description = "취소 사유") @PlainText(max = 500) String reason) {}
}
