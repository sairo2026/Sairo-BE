package com.sairo.be.domain.ops.service;

import com.sairo.be.domain.office.dto.response.OwnerTransferResponse;
import com.sairo.be.domain.office.service.OwnerTransferService;
import com.sairo.be.domain.ops.dto.response.OpsOwnerTransferListResponse;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsOwnerTransferQueryRepository;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OperatorOwnerTransferService {

  static final Set<String> STATUSES =
      Set.of("PENDING", "UNDER_REVIEW", "COMPLETED", "REJECTED", "WITHDRAWN", "EXPIRED");
  private static final List<String> DEFAULT_STATUSES = List.of("UNDER_REVIEW");

  private final OwnerTransferService transferService;
  private final OpsAuditLogRepository auditLogRepository;
  private final OpsOwnerTransferQueryRepository queries;
  private final Clock clock;

  public OperatorOwnerTransferService(
      OwnerTransferService transferService,
      OpsAuditLogRepository auditLogRepository,
      OpsOwnerTransferQueryRepository queries,
      Clock clock) {
    this.transferService = transferService;
    this.auditLogRepository = auditLogRepository;
    this.queries = queries;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public OpsOwnerTransferListResponse list(String status, Long officeId, PageQuery page) {
    List<String> statuses = statuses(status);
    Long office = OpsQueryParams.positiveId("officeId", officeId);
    List<OpsOwnerTransferListResponse.Item> items =
        queries.list(statuses, office, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new OpsOwnerTransferListResponse.Item(
                        row.id(),
                        row.officeId(),
                        row.officeName(),
                        row.fromDisplayName(),
                        row.toDisplayName(),
                        row.status(),
                        TimeRules.toDisplayOffset(row.requestedAt()),
                        TimeRules.toDisplayOffset(row.acceptDeadlineAt()),
                        TimeRules.toDisplayOffset(row.acceptedAt()),
                        TimeRules.toDisplayOffset(row.completedAt())))
            .toList();
    return new OpsOwnerTransferListResponse(
        items, page.page(), page.size(), queries.count(statuses, office));
  }

  private static List<String> statuses(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_STATUSES;
    }
    List<String> values = Arrays.stream(raw.split(",", -1)).map(String::strip).distinct().toList();
    if (!STATUSES.containsAll(values)) {
      throw OpsQueryParams.invalid("status", FieldErrorCode.NOT_ALLOWED, "허용되지 않는 status 값입니다.");
    }
    return values;
  }

  @Transactional
  public OwnerTransferResponse approve(long operatorId, Long transferId) {
    OwnerTransferResponse result = transferService.complete(transferId);
    auditLogRepository.insert(
        operatorId, "OWNER_TRANSFER_APPROVED", "OWNER_TRANSFER", transferId, null, clock.instant());
    return result;
  }
}
