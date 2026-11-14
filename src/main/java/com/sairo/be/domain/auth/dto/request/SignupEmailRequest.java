package com.sairo.be.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "이메일 회원가입 완료 요청")
public record SignupEmailRequest(
    @Schema(description = "이메일 인증 증명 티켓") @NotBlank String verifiedEmailTicket,
    @Schema(description = "휴대폰 본인확인 증명 티켓. 본인확인이 필수일 때만 필요하다") String verifiedPhoneTicket,
    @Schema(description = "휴대폰 번호. 본인확인이 필수가 아닐 때 필수이며 미인증으로 저장한다") @Size(max = 30) String phone,
    @Schema(description = "로그인 비밀번호. 코드 포인트 기준 8~12자이며 공백도 길이에 포함한다") @NotBlank String password,
    @Schema(description = "담당자 표시 이름", example = "홍길동") @NotBlank @Size(max = 200) String displayName,
    @Schema(description = "동의한 약관 항목") @NotNull @Valid List<AgreedTermRequest> agreedTerms) {}
