package com.sairo.be.domain.ops.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** 공지·정책 문서·회사 제공 정보 응답이다(OPS-08). */
public final class OpsContentResponses {

  private OpsContentResponses() {}

  @Schema(description = "공지 목록 항목")
  public record AnnouncementItem(
      Long announcementId,
      String title,
      String content,
      int versionNo,
      Long previousVersionId,
      String audienceType,
      String audienceStatus,
      Long audienceOfficeId,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      String importance,
      String detailUrl,
      boolean marketing,
      String status,
      OffsetDateTime scheduledAt,
      OffsetDateTime publishedAt,
      OffsetDateTime endedAt,
      OffsetDateTime createdAt,
      Long createdByOperatorId,
      Long reviewedByOperatorId) {}

  @Schema(description = "공지 목록")
  public record AnnouncementListResponse(
      List<AnnouncementItem> items, int page, int size, long totalElements) {}

  @Schema(description = "공지 작성·게시 결과")
  public record AnnouncementStateResponse(
      Long announcementId,
      int versionNo,
      String status,
      OffsetDateTime scheduledAt,
      OffsetDateTime publishedAt) {}

  @Schema(description = "공지 검토 단계 전환 결과")
  public record AnnouncementSubmitReviewResponse(
      Long announcementId, int versionNo, String status, Long reviewedByOperatorId) {}

  @Schema(description = "공지 종료 결과. 예약 공지는 예약 취소로 종료한다")
  public record AnnouncementEndResponse(
      Long announcementId, int versionNo, String status, OffsetDateTime endedAt) {}

  @Schema(description = "게시본 수정으로 만든 새 버전 초안")
  public record AnnouncementRevisionResponse(
      Long announcementId, int versionNo, Long previousVersionId, String status) {}

  @Schema(description = "공지 게시 전 미리보기")
  public record AnnouncementPreviewResponse(
      Long announcementId,
      long targetOfficeCount,
      long marketingExcludedCount,
      String renderedContent) {}

  @Schema(description = "정책 문서 목록 항목")
  public record PolicyDocumentItem(
      Long documentId,
      String docType,
      String version,
      String status,
      String body,
      String changeSummary,
      OffsetDateTime noticeAt,
      boolean requiresConsent,
      OffsetDateTime effectiveAt,
      OffsetDateTime publishedAt,
      Long reviewedByOperatorId,
      Long publishedByOperatorId,
      Long createdByOperatorId) {}

  @Schema(description = "정책 문서 목록")
  public record OpsPolicyDocumentListResponse(
      List<PolicyDocumentItem> items, int page, int size, long totalElements) {}

  @Schema(description = "정책 문서 등록 결과")
  public record PolicyDocumentCreateResponse(
      Long documentId, String docType, String version, String status, OffsetDateTime effectiveAt) {}

  @Schema(description = "정책 문서 검토 단계 전환 결과")
  public record PolicyDocumentSubmitReviewResponse(
      Long documentId, String version, String status, Long reviewedByOperatorId) {}

  @Schema(description = "정책 문서 게시 결과")
  public record PolicyDocumentPublishResponse(
      Long documentId, String version, String status, OffsetDateTime publishedAt) {}

  @Schema(description = "회사 제공 정보 버전")
  public record CompanyServiceInfoItem(
      Long infoId,
      int versionNo,
      String businessName,
      String representativeName,
      String businessNumber,
      String address,
      String contactPhone,
      String ecommerceNotice,
      String supportEmail,
      String supportHours,
      String emergencyContact,
      String privacyOfficer,
      String privacyContact,
      String privacyRequestPath,
      String cancellationPath,
      OffsetDateTime inquiryDeliveryTestedAt,
      Long reviewedByOperatorId,
      OffsetDateTime publishedAt) {}

  @Schema(description = "회사 제공 정보 버전 목록과 운영 출시 준비 여부")
  public record CompanyServiceInfoResponse(
      List<CompanyServiceInfoItem> items,
      boolean launchReady,
      List<String> missingItems,
      int page,
      int size,
      long totalElements) {}

  @Schema(description = "회사 제공 정보 등록 결과. 게시는 문의 수신 시험 성공 때 한다")
  public record CompanyServiceInfoCreateResponse(
      Long infoId,
      int versionNo,
      OffsetDateTime inquiryDeliveryTestedAt,
      Long reviewedByOperatorId,
      OffsetDateTime publishedAt) {}

  @Schema(description = "문의 수신 시험 기록 결과")
  public record InquiryTestResponse(
      Long infoId,
      OffsetDateTime inquiryDeliveryTestedAt,
      Long reviewedByOperatorId,
      OffsetDateTime publishedAt) {}
}
