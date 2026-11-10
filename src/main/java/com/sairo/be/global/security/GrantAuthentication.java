package com.sairo.be.global.security;

import com.sairo.be.global.security.access.PrincipalType;
import com.sairo.be.global.security.access.TypedAuthentication;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class GrantAuthentication extends AbstractAuthenticationToken
    implements TypedAuthentication {

  private final GrantPrincipal principal;

  public GrantAuthentication(GrantPrincipal principal) {
    super(List.of(new SimpleGrantedAuthority("ROLE_GRANT_HOLDER")));
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public GrantPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public String getName() {
    return String.valueOf(principal.userId());
  }

  @Override
  public PrincipalType principalType() {
    return PrincipalType.GRANT_HOLDER;
  }
}
