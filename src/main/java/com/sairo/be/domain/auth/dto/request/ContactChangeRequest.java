package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "인증을 거친 이메일 또는 휴대폰 변경 요청. email과 phone 중 하나만 보낸다")
public record ContactChangeRequest(
    @Schema(description = "변경할 이메일") @Email @Size(max = 254) String email,
    @Schema(description = "변경할 휴대폰") @Size(max = 30) String phone,
    @Schema(description = "새 값에 대한 인증 티켓") @NotBlank String verifiedTicket) {}
