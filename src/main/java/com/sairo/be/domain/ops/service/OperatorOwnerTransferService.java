package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.office.dto.response.OwnerTransferResponse;
import com.sairo.be.domain.office.service.OwnerTransferService;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import java.time.Clock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OperatorOwnerTransferService {

  private final OwnerTransferService transferService;
  private final OpsAuditLogRepository auditLogRepository;
  private final Clock clock;

  public OperatorOwnerTransferService(
      OwnerTransferService transferService, OpsAuditLogRepository auditLogRepository, Clock clock) {
    this.transferService = transferService;
    this.auditLogRepository = auditLogRepository;
    this.clock = clock;
  }

  @Transactional
  public OwnerTransferResponse approve(long operatorId, Long transferId) {
    OwnerTransferResponse result = transferService.complete(transferId);
    auditLogRepository.insert(
        operatorId, "OWNER_TRANSFER_APPROVED", "OWNER_TRANSFER", transferId, null, clock.instant());
    return result;
  }
}
