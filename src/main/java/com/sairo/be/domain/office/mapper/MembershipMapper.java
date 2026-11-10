package com.sairo.be.domain.office.mapper;

import com.sairo.be.domain.office.dto.response.MembershipInvitationResponse;
import com.sairo.be.domain.office.dto.response.MembershipResultResponse;
import com.sairo.be.domain.office.dto.response.MembershipRoleUpdateResponse;
import com.sairo.be.domain.office.repository.MembershipRecordRepository.Row;
import com.sairo.be.global.support.TimeRules;

public final class MembershipMapper {

  private MembershipMapper() {}

  public static MembershipResultResponse toResult(Row row) {
    return new MembershipResultResponse(
        row.id(),
        row.status().name(),
        row.role().name(),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }

  public static MembershipRoleUpdateResponse toRoleResult(Row row) {
    return new MembershipRoleUpdateResponse(
        row.id(),
        row.role().name(),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }

  public static MembershipInvitationResponse toInvitation(Row row) {
    return new MembershipInvitationResponse(
        row.id(),
        TimeRules.toDisplayOffset(row.invitationExpiresAt()),
        row.status().name(),
        row.role().name(),
        row.officeId(),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }
}
