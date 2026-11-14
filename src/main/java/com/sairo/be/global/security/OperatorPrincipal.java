package com.sairo.be.global.security;

import java.io.Serializable;
import java.util.List;

public record OperatorPrincipal(Long operatorId, String name, List<OperatorRole> roles)
    implements Serializable {

  public boolean hasRole(OperatorRole role) {
    return roles.contains(role);
  }
}
