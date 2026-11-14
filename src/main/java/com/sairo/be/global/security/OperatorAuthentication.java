package com.sairo.be.global.security;

import com.sairo.be.global.security.access.PrincipalType;
import com.sairo.be.global.security.access.TypedAuthentication;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class OperatorAuthentication extends AbstractAuthenticationToken
    implements TypedAuthentication {

  public static final String NAME_PREFIX = "operator:";

  private final OperatorPrincipal principal;

  public OperatorAuthentication(OperatorPrincipal principal) {
    super(List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    this.principal = principal;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public OperatorPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public String getName() {
    return NAME_PREFIX + principal.operatorId();
  }

  @Override
  public PrincipalType principalType() {
    return PrincipalType.OPERATOR;
  }
}
