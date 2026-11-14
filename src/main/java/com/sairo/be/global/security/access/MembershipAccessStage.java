package com.sairo.be.global.security.access;

import com.sairo.be.global.error.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class MembershipAccessStage implements AccessStage {

  @Override
  public AccessStageType type() {
    return AccessStageType.MEMBERSHIP_STATUS;
  }

  @Override
  public AccessStageResult evaluate(AccessRequest request) {
    if (request.permission().requiresApprovedMembership()
        && !request.subject().hasApprovedMembership()) {
      return AccessStageResult.deny(ErrorCode.FORBIDDEN);
    }
    return AccessStageResult.allow();
  }
}
