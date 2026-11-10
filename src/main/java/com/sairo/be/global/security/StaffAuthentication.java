package com.sairo.be.global.security;

import com.sairo.be.global.security.access.AccessSubject;
import com.sairo.be.global.security.access.PrincipalType;
import com.sairo.be.global.security.access.TypedAuthentication;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class StaffAuthentication extends AbstractAuthenticationToken
    implements TypedAuthentication {

  private final StaffPrincipal principal;

  public StaffAuthentication(StaffPrincipal principal) {
    super(List.of(new SimpleGrantedAuthority("ROLE_STAFF")));
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public StaffPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public String getName() {
    return String.valueOf(principal.userId());
  }

  @Override
  public PrincipalType principalType() {
    return PrincipalType.MEMBER;
  }

  @Override
  public AccessSubject accessSubject() {
    return AccessSubject.member(
        principal.userId(), principal.membershipId(), principal.officeId(), principal.role());
  }
}
