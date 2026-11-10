package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.entity.OfficeRequestSource;
import com.sairo.be.domain.office.repository.MembershipRecordRepository.Row;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.error.FieldErrorItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class MembershipRules {

  static final Duration REQUEST_VALIDITY = Duration.ofDays(30);
  static final Duration INVITATION_VALIDITY = Duration.ofDays(7);

  private MembershipRules() {}

  static Instant expiresAt(Row row) {
    return row.requestSource() == OfficeRequestSource.INVITE
        ? row.invitationExpiresAt()
        : row.createdAt().plus(REQUEST_VALIDITY);
  }

  static boolean isExpiredPending(Row row, Instant now) {
    return row.status() == OfficeMembershipStatus.PENDING && !expiresAt(row).isAfter(now);
  }

  static void requireLivePending(Row row, Instant now) {
    if (row.status() != OfficeMembershipStatus.PENDING || isExpiredPending(row, now)) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  static OfficeMembershipRole requestedRole(OfficeMembershipRole role) {
    OfficeMembershipRole resolved = role == null ? OfficeMembershipRole.AGENT : role;
    if (resolved == OfficeMembershipRole.OWNER) {
      throw invalid("role", FieldErrorCode.NOT_ALLOWED, "대표 역할은 선택할 수 없습니다.");
    }
    return resolved;
  }

  static void requireQualification(
      OfficeMembershipRole role,
      String qualificationNumber,
      Boolean employmentReportConfirmed,
      Boolean trainingConfirmed) {
    if (!Boolean.TRUE.equals(employmentReportConfirmed)) {
      throw invalid("employmentReportConfirmed", FieldErrorCode.REQUIRED, "고용신고 확인이 필요합니다.");
    }
    if (!Boolean.TRUE.equals(trainingConfirmed)) {
      throw invalid("trainingConfirmed", FieldErrorCode.REQUIRED, "교육 이수 확인이 필요합니다.");
    }
    boolean hasNumber = qualificationNumber != null && !qualificationNumber.isBlank();
    if (role == OfficeMembershipRole.AGENT) {
      if (!hasNumber) {
        throw invalid("qualificationNumber", FieldErrorCode.REQUIRED, "자격증 번호가 필요합니다.");
      }
      if (qualificationNumber.strip().length() > 30) {
        throw invalid("qualificationNumber", FieldErrorCode.TOO_LONG, "30자 이하로 입력해야 합니다.");
      }
    } else if (hasNumber) {
      throw invalid("qualificationNumber", FieldErrorCode.NOT_ALLOWED, "중개보조원은 자격증 번호를 받지 않습니다.");
    }
  }

  static String normalizedNumber(OfficeMembershipRole role, String qualificationNumber) {
    return role == OfficeMembershipRole.AGENT ? qualificationNumber.strip() : null;
  }

  static BusinessException invalid(String field, FieldErrorCode code, String message) {
    return BusinessException.withFieldErrors(
        ErrorCode.INVALID_REQUEST, List.of(new FieldErrorItem(field, code, message)));
  }
}
