package com.sairo.be.domain.ops.dto.request;

import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** 운영 콘솔의 운영자 관리·회원 보안 요청 본문이다. */
public final class OpsConsoleRequests {

  private static final String ROLE_PATTERN =
      "SECURITY_ADMIN|REVIEW|SUPPORT|BILLING|MIGRATION|PRIVACY|OPS|EXEC_VIEW";

  private OpsConsoleRequests() {}

  @Schema(description = "운영자 초대 생성 요청")
  public record OperatorInvitationCreateRequest(
      @Schema(description = "초대받는 사람 이름") @NotBlank @PlainText(max = 50) String inviteeName,
      @Schema(description = "초대 이메일") @NotBlank @Email @Size(max = 254) String inviteeEmail,
      @Schema(description = "수락 시 부여할 역할") @NotEmpty @Size(max = 8) List<@NotNull @Pattern(regexp = ROLE_PATTERN) String> roles) {}

  @Schema(description = "운영자 역할 배정·회수 요청. 보낸 목록이 배정 뒤의 전체 역할이다.")
  public record OperatorRoleAssignRequest(
      @Schema(description = "배정할 역할 전체") @NotEmpty @Size(max = 8) List<@NotNull @Pattern(regexp = ROLE_PATTERN) String> roles) {}

  @Schema(description = "운영자 비활성화 요청")
  public record OperatorDisableRequest(
      @Schema(description = "비활성화 사유") @NotBlank @PlainText(max = 1000) String reason) {}

  @Schema(description = "회원 긴급 접근 중단 요청")
  public record MemberEmergencySuspendRequest(
      @Schema(description = "중단 사유") @NotBlank @PlainText(max = 1000) String reason,
      @Schema(
              description = "중단 범위",
              allowableValues = {"ALL_SESSIONS", "SPECIFIC_SESSION"})
          @NotBlank @Pattern(regexp = "ALL_SESSIONS|SPECIFIC_SESSION") String scope,
      @Schema(description = "SPECIFIC_SESSION일 때 회원 상세의 sessions[].sessionId") @Size(max = 100) String sessionId,
      @Schema(description = "재검토 예정 시각") @NotNull OffsetDateTime reviewAt) {}
}
