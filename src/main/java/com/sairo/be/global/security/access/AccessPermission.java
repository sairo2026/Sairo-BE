package com.sairo.be.global.security.access;

import com.sairo.be.global.error.ErrorCode;

public enum AccessPermission {
  PUBLIC(null, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN),
  AUTHENTICATED(PrincipalType.MEMBER, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN),
  STAFF(PrincipalType.MEMBER, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN),
  AGENT_OR_OWNER(PrincipalType.MEMBER, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN),
  OWNER(PrincipalType.MEMBER, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN),
  RETURN_GRANT(
      PrincipalType.GRANT_HOLDER, ErrorCode.UNAUTHENTICATED, ErrorCode.RETURN_GRANT_INVALID),
  ARCHIVE_GRANT(
      PrincipalType.GRANT_HOLDER, ErrorCode.UNAUTHENTICATED, ErrorCode.ARCHIVE_GRANT_INVALID),
  OPERATOR(PrincipalType.OPERATOR, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN),
  GUEST_LINK(
      PrincipalType.GUEST_LINK, ErrorCode.PUBLIC_LINK_NOT_FOUND, ErrorCode.PUBLIC_LINK_NOT_FOUND),
  APPROVER_SESSION(PrincipalType.APPROVER_SESSION, ErrorCode.UNAUTHENTICATED, ErrorCode.FORBIDDEN),
  EXTERNAL_WEBHOOK(
      PrincipalType.EXTERNAL_WEBHOOK, ErrorCode.WEBHOOK_AUTH_FAILED, ErrorCode.WEBHOOK_AUTH_FAILED);

  private final PrincipalType requiredPrincipalType;
  private final ErrorCode anonymousDenial;
  private final ErrorCode mismatchDenial;

  AccessPermission(
      PrincipalType requiredPrincipalType, ErrorCode anonymousDenial, ErrorCode mismatchDenial) {
    this.requiredPrincipalType = requiredPrincipalType;
    this.anonymousDenial = anonymousDenial;
    this.mismatchDenial = mismatchDenial;
  }

  public PrincipalType requiredPrincipalType() {
    return requiredPrincipalType;
  }

  public ErrorCode anonymousDenial() {
    return anonymousDenial;
  }

  public ErrorCode mismatchDenial() {
    return mismatchDenial;
  }

  public boolean requiresApprovedMembership() {
    return this == STAFF || this == AGENT_OR_OWNER || this == OWNER;
  }
}
