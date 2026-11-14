package com.sairo.be.global.security;

import com.sairo.be.global.security.access.PrincipalType;
import com.sairo.be.global.security.access.TypedAuthentication;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class ApproverAuthentication extends AbstractAuthenticationToken
    implements TypedAuthentication {

  public static final String NAME_PREFIX = "approver:";

  private final ApproverPrincipal principal;

  public ApproverAuthentication(ApproverPrincipal principal) {
    super(List.of(new SimpleGrantedAuthority("ROLE_APPROVER")));
    this.principal = principal;
    setAuthenticated(true);
  }

  public static String principalName(Long profileId) {
    return NAME_PREFIX + profileId;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public ApproverPrincipal getPrincipal() {
    return principal;
  }

  @Override
  public String getName() {
    return principalName(principal.profileId());
  }

  @Override
  public PrincipalType principalType() {
    return PrincipalType.APPROVER_SESSION;
  }
}
