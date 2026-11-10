package com.sairo.be.global.security.access;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.StaffRole;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class RoleAccessStage implements AccessStage {

  @Override
  public AccessStageType type() {
    return AccessStageType.ROLE_PERMISSION;
  }

  @Override
  public AccessStageResult evaluate(AccessRequest request) {
    StaffRole role = request.subject().role();
    boolean allowed =
        switch (request.permission()) {
          case STAFF -> role != null;
          case AGENT_OR_OWNER -> role != null && role.isAgentOrOwner();
          case OWNER -> role == StaffRole.OWNER;
          default -> true;
        };
    return allowed ? AccessStageResult.allow() : AccessStageResult.deny(ErrorCode.FORBIDDEN);
  }
}
