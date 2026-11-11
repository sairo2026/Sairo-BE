package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** 시작 고객 합의와 가격 변경 안내 요청 본문이다(OPS-21·22). */
public final class OpsPricingRequests {

  private OpsPricingRequests() {}

  @Schema(description = "시작 고객 합의 등록. 등록은 승인이 아니다")
  public record StartCustomerAgreementCreateRequest(
      @Schema(description = "대상 사무소") @NotNull @Positive Long officeId,
      @Schema(description = "시작 고객 체험 이벤트(대상 START_CUSTOMER, 예약·진행 중)") @NotNull @Positive Long trialEventId,
      @Schema(description = "시작 고객 전용 가격 버전(대상 START_CUSTOMER, 예약·시행 중)") @NotNull @Positive Long priceScheduleId,
      @Schema(description = "가격 보장 개월 수. 생략하면 12") @Min(1) @Max(24) Integer guaranteeMonths,
      @Schema(description = "합의서 체결일(KST)") @NotNull LocalDate contractedOn,
      @Schema(description = "합의서 참조번호") @NotBlank @PlainText(max = 100) String contractReference,
      @Schema(description = "주간 사용 확인. 참이어야 한다") @NotNull @AssertTrue Boolean weeklyUseConfirmed,
      @Schema(description = "월 1회 피드백 인터뷰 확인. 참이어야 한다") @NotNull @AssertTrue Boolean monthlyInterviewConfirmed,
      @Schema(description = "사례 공개 선택 동의. 생략하면 거짓") Boolean casePublicationConsent,
      @Schema(description = "합의서 문안 버전") @NotBlank @PlainText(max = 20)
          String agreementTextVersion) {}

  @Schema(description = "시작 고객 합의 취소")
  public record StartCustomerAgreementRevokeRequest(
      @Schema(description = "취소 사유") @NotBlank @PlainText(max = 500) String reason) {}

  @Schema(description = "가격 변경 안내 생성. officeIds가 없으면 구독이 있는 모든 사무소가 대상이다")
  public record PriceChangeNoticeCreateRequest(
      @Schema(description = "안내를 만들 사무소. 최대 500개") @Size(max = 500) List<@NotNull @Positive Long> officeIds) {}
}
