package com.sairo.be.domain.office.dto.request;

import com.sairo.be.domain.office.entity.OfficeEntityType;
import com.sairo.be.global.support.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "사무소 등록 신청 작성 요청")
public record OfficeRegistrationCreateRequest(
    @Schema(description = "사업자 유형") @NotNull OfficeEntityType entityType,
    @Schema(description = "등록관청", example = "서울특별시 종로구") @NotBlank @PlainText(max = 100)
        String registrationAuthority,
    @Schema(description = "개설등록번호", example = "11110-2026-00001") @NotBlank @PlainText(max = 30)
        String realEstateLicenseNumber,
    @Schema(description = "사업자등록번호 10자리(하이픈 허용)", example = "123-45-67890")
        @NotBlank @PlainText(max = 12)
        String businessRegistrationNumber,
    @Schema(description = "신청 사무소명") @NotBlank @PlainText(max = 100) String requestedOfficeName,
    @Schema(description = "신청 대표자명") @NotBlank @PlainText(max = 50)
        String requestedRepresentativeName,
    @Schema(description = "신청 연락처") @NotBlank @PlainText(max = 30)
        String requestedRepresentativePhone,
    @Schema(description = "신청 주소") @NotBlank @PlainText(max = 300) String requestedAddress,
    @Schema(description = "신청자의 등록관계") @NotBlank @PlainText(max = 30)
        String applicantRegistrationRelation) {}
