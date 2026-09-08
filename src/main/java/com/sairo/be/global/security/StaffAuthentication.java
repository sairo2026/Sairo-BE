package com.sairo.be.global.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class StaffAuthentication extends AbstractAuthenticationToken {

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
}
