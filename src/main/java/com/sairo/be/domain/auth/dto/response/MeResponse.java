package com.sairo.be.domain.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "내 계정 조회 응답")
public record MeResponse(
    @Schema(description = "계정 내부 식별자") Long userId,
    @Schema(description = "표시 이름") String displayName,
    @Schema(description = "로그인용 이메일") String email,
    @Schema(description = "이메일 인증 여부") boolean emailVerified,
    @Schema(description = "휴대폰 번호") String phone,
    @Schema(description = "휴대폰 본인확인 여부") boolean phoneVerified,
    @Schema(description = "카카오 로그인수단 연결 여부") boolean kakaoLinked,
    @Schema(description = "현재 연결된 로그인수단") List<String> loginMethods,
    @Schema(description = "계정 상태") String accountStatus,
    @Schema(description = "알림 수신 설정의 현재 값") NotificationPreferencesResponse notificationPreferences,
    @Schema(description = "계정 생성 일시") OffsetDateTime createdAt,
    @Schema(description = "약관 코드마다 가장 최근 동의·철회 기록")
        List<TermsAgreementSummaryResponse> termsAgreements) {

  @Schema(description = "알림 수신 설정")
  public record NotificationPreferencesResponse(
      @Schema(description = "알림 유형별 수신 여부") Map<String, Boolean> channels,
      @Schema(description = "끌 수 없는 알림 설정 키") List<String> mandatoryTypes) {}

  @Schema(description = "약관 동의 요약")
  public record TermsAgreementSummaryResponse(
      String termsCode,
      String termsName,
      boolean required,
      boolean withdrawable,
      String termsVersion,
      boolean agreed,
      OffsetDateTime recordedAt) {}
}
