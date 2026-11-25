package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.global.port.PromotionOperatorPort;
import com.sairo.be.global.security.OperatorRole;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!migrate")
@Component
public class PromotionOperatorAdapter implements PromotionOperatorPort {
  private final OperatorAccountRepository operators;
  private final OpsAuditLogRepository audit;

  public PromotionOperatorAdapter(
      OperatorAccountRepository operators, OpsAuditLogRepository audit) {
    this.operators = operators;
    this.audit = audit;
  }

  @Override
  public boolean canManageBilling(long operatorId) {
    return operators.isActiveWithRole(operatorId, OperatorRole.BILLING);
  }

  @Override
  public void record(
      long operatorId,
      String action,
      String targetType,
      long targetId,
      String details,
      Instant at) {
    audit.insert(operatorId, action, targetType, targetId, details, at);
  }
}
