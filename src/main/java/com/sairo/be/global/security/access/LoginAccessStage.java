package com.sairo.be.global.security.access;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class LoginAccessStage implements AccessStage {

  @Override
  public AccessStageType type() {
    return AccessStageType.LOGIN;
  }

  @Override
  public AccessStageResult evaluate(AccessRequest request) {
    AccessPermission permission = request.permission();
    AccessSubject subject = request.subject();
    if (subject.isAnonymous()) {
      return AccessStageResult.deny(permission.anonymousDenial());
    }
    if (subject.type() != permission.requiredPrincipalType()) {
      return AccessStageResult.deny(permission.mismatchDenial());
    }
    return AccessStageResult.allow();
  }
}
