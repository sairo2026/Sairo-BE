package com.sairo.be.global.security.access;

public interface TypedAuthentication {

  PrincipalType principalType();

  default AccessSubject accessSubject() {
    return AccessSubject.of(principalType());
  }
}
