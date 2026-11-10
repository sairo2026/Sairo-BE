package com.sairo.be.domain.ops.mapper;

import com.sairo.be.domain.ops.dto.response.OperatorInvitationAcceptResponse;
import com.sairo.be.domain.ops.dto.response.OperatorInvitationPreviewResponse;
import com.sairo.be.domain.ops.dto.response.OperatorMfaEnrollmentResponse;
import com.sairo.be.domain.ops.dto.response.OpsMeResponse;
import com.sairo.be.domain.ops.dto.response.OpsMfaVerifyResponse;
import com.sairo.be.domain.ops.repository.OperatorInvitationRepository;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TimeRules;
import java.time.Instant;
import java.util.List;

public final class OpsAuthMapper {

  private OpsAuthMapper() {}

  public static List<String> roleNames(List<OperatorRole> roles) {
    return roles.stream().map(Enum::name).toList();
  }

  public static OpsMeResponse toMeResponse(long operatorId, String name, List<OperatorRole> roles) {
    return new OpsMeResponse(operatorId, name, roleNames(roles));
  }

  public static OpsMfaVerifyResponse toVerifyResponse(
      OperatorPrincipal principal, Instant idleExpiresAt, Instant absoluteExpiresAt) {
    return new OpsMfaVerifyResponse(
        principal.operatorId(),
        principal.name(),
        roleNames(principal.roles()),
        TimeRules.toDisplayOffset(idleExpiresAt),
        TimeRules.toDisplayOffset(absoluteExpiresAt));
  }

  public static OperatorInvitationPreviewResponse toPreviewResponse(
      OperatorInvitationRepository.Row invitation, List<OperatorRole> roles) {
    return new OperatorInvitationPreviewResponse(
        invitation.inviteeName(),
        roleNames(roles),
        TimeRules.toDisplayOffset(invitation.expiresAt()));
  }

  public static OperatorMfaEnrollmentResponse toEnrollmentResponse(
      String otpauthUri, String secret, Instant enrollmentExpiresAt) {
    return new OperatorMfaEnrollmentResponse(
        otpauthUri, secret, TimeRules.toDisplayOffset(enrollmentExpiresAt));
  }

  public static OperatorInvitationAcceptResponse toAcceptResponse(
      long operatorId, String name, List<OperatorRole> roles, Instant acceptedAt) {
    return new OperatorInvitationAcceptResponse(
        operatorId, name, roleNames(roles), TimeRules.toDisplayOffset(acceptedAt));
  }
}
