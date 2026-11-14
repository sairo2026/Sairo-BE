package com.sairo.be.domain.office.dto.request;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Schema(description = "고용명부 등록 요청")
public record EmploymentRosterCreateRequest(
    @Schema(description = "고용인 이름") @NotBlank @PlainText(max = 50) String personName,
    @Schema(description = "법정 신분, AGENT 또는 DEPUTY") @NotNull OfficeMembershipRole legalQualification,
    @Schema(description = "고용 시작일") @NotNull LocalDate employmentStartDate,
    @Schema(description = "확인 출처, REGISTRATION_DOC 또는 MANUAL_ENTRY") @NotBlank String confirmationSource,
    @Schema(description = "계정이 있는 고용인의 소속 ID(선택)", nullable = true) Long membershipId) {}
