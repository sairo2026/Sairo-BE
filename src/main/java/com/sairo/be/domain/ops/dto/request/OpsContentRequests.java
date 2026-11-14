package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;

/** 공지·정책 문서·회사 제공 정보 요청 본문이다(OPS-08). */
public final class OpsContentRequests {

  private OpsContentRequests() {}

  @Schema(description = "공지 작성과 게시본 수정. 대상 종류에 따라 대상 구독 상태나 대상 사무소를 함께 보낸다")
  public record AnnouncementRequest(
      @Schema(description = "제목") @PlainText(max = 200) String title,
      @Schema(description = "본문") @PlainText(max = 10_000) String content,
      @Schema(description = "ALL, SUBSCRIPTION_STATUS, OFFICE") @NotNull String audienceType,
      @Schema(description = "SUBSCRIPTION_STATUS일 때만 대상 구독 상태") String audienceStatus,
      @Schema(description = "OFFICE일 때만 대상 사무소") @Positive Long audienceOfficeId,
      @Schema(description = "노출 시작") @NotNull OffsetDateTime startsAt,
      @Schema(description = "노출 종료. 시작보다 뒤") @NotNull OffsetDateTime endsAt,
      @Schema(description = "NORMAL, IMPORTANT, CRITICAL") @NotNull String importance,
      @Schema(description = "상세 링크. https:// 또는 /로 시작") @PlainText(min = 0, max = 2_000)
          String detailUrl,
      @Schema(description = "마케팅 여부. 미동의자에게 발송하지 않는다") @NotNull Boolean marketing) {}

  @Schema(description = "공지 게시·예약. 본문이 없거나 scheduledAt이 null이면 즉시 게시")
  public record AnnouncementPublishRequest(
      @Schema(description = "예약 게시 시각. 현재보다 뒤") OffsetDateTime scheduledAt) {}

  @Schema(description = "정책 문서 새 버전")
  public record PolicyDocumentCreateRequest(
      @Schema(
              description =
                  "TERMS, PRIVACY, PROCESSING_OUTSOURCING, ARCHIVE, REFUND, SUBPROCESSORS, PRICING")
          @NotNull String docType,
      @Schema(description = "버전") @PlainText(max = 20) String version,
      @Schema(description = "본문") @PlainText(max = 200_000) String body,
      @Schema(description = "이전 버전과 달라진 내용") @PlainText(max = 2_000) String changeSummary,
      @Schema(description = "공지 시각. PRICING은 시행 30일 전 이전") @NotNull OffsetDateTime noticeAt,
      @Schema(description = "새 버전 적용의 명시적 동의 필요 여부") @NotNull Boolean requiresConsent,
      @Schema(description = "시행 시각") @NotNull OffsetDateTime effectiveAt) {}

  @Schema(description = "실제 회사·지원·개인정보 창구 정보 새 버전")
  public record CompanyServiceInfoRequest(
      @Schema(description = "상호") @PlainText(max = 200) String businessName,
      @Schema(description = "대표자") @PlainText(max = 100) String representativeName,
      @Schema(description = "사업자번호") @PlainText(max = 20) String businessNumber,
      @Schema(description = "주소") @PlainText(max = 500) String address,
      @Schema(description = "사업자 연락처") @PlainText(max = 30) String contactPhone,
      @Schema(description = "해당할 때의 통신판매 신고정보") @PlainText(min = 0, max = 100)
          String ecommerceNotice,
      @Schema(description = "지원 이메일") @PlainText(max = 254) String supportEmail,
      @Schema(description = "운영시간") @PlainText(max = 500) String supportHours,
      @Schema(description = "긴급 연락처") @PlainText(max = 500) String emergencyContact,
      @Schema(description = "개인정보 책임자") @PlainText(max = 100) String privacyOfficer,
      @Schema(description = "개인정보 연락처") @PlainText(max = 500) String privacyContact,
      @Schema(description = "개인정보 요청 접수 경로") @PlainText(max = 500) String privacyRequestPath,
      @Schema(description = "구독 해지 경로") @PlainText(max = 500) String cancellationPath) {}

  @Schema(description = "실제 지원 이메일 문의 수신 시험 결과")
  public record InquiryTestRequest(
      @Schema(description = "SUCCESS 또는 FAILED") @NotNull String result) {}
}
