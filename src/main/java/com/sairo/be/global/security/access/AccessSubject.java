package com.sairo.be.global.security.access;

import com.sairo.be.global.security.StaffRole;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

public record AccessSubject(
    PrincipalType type, Long userId, Long membershipId, Long officeId, StaffRole role) {

  public static AccessSubject anonymous() {
    return new AccessSubject(null, null, null, null, null);
  }

  public static AccessSubject of(PrincipalType type) {
    return new AccessSubject(type, null, null, null, null);
  }

  public static AccessSubject member(
      Long userId, Long membershipId, Long officeId, StaffRole role) {
    return new AccessSubject(PrincipalType.MEMBER, userId, membershipId, officeId, role);
  }

  public static AccessSubject from(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return anonymous();
    }
    if (authentication instanceof TypedAuthentication typed) {
      return typed.accessSubject();
    }
    return anonymous();
  }

  public boolean isAnonymous() {
    return type == null;
  }

  public boolean hasApprovedMembership() {
    return type == PrincipalType.MEMBER && membershipId != null && officeId != null && role != null;
  }
}
