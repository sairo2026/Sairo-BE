package com.sairo.be.domain.office.dto.request;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "참여 요청과 초대 수락, 재신청의 자격 확인 입력")
public record MembershipCreateRequest(
    @Schema(description = "역할, 생략하면 AGENT") OfficeMembershipRole role,
    @Schema(description = "자격증 번호(AGENT 필수)") String qualificationNumber,
    @Schema(description = "고용신고 확인") Boolean employmentReportConfirmed,
    @Schema(description = "교육 이수 확인") Boolean trainingConfirmed) {}
