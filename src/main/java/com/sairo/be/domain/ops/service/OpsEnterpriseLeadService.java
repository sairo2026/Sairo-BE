package com.sairo.be.domain.ops.service;

import static com.sairo.be.global.support.TimeRules.toDisplayOffset;

import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OperatorEnterpriseLeadQuoteRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OpsEnterpriseLeadCloseRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OpsEnterpriseLeadContractRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.OpsEnterpriseLeadReopenRequest;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.EnterpriseLeadQuoteResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadAssignResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadCloseResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadConsultationStartResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadContractResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadHistory;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadItem;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseLeadReopenResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsEnterpriseQuote;
import com.sairo.be.domain.ops.repository.OperatorAccountRepository;
import com.sairo.be.domain.ops.repository.OpsAuditLogRepository;
import com.sairo.be.domain.ops.repository.OpsSupportQueryRepository;
import com.sairo.be.domain.ops.repository.OpsSupportQueryRepository.LeadFilter;
import com.sairo.be.domain.support.entity.EnterpriseLead;
import com.sairo.be.domain.support.entity.EnterpriseLeadStatusHistory;
import com.sairo.be.domain.support.entity.EnterpriseQuote;
import com.sairo.be.domain.support.entity.LeadStatus;
import com.sairo.be.domain.support.repository.EnterpriseLeadRepository;
import com.sairo.be.domain.support.repository.EnterpriseLeadStatusHistoryRepository;
import com.sairo.be.domain.support.repository.EnterpriseQuoteRepository;
import com.sairo.be.domain.support.repository.SupportDirectoryRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.IfMatchVersion;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 운영 콘솔의 기업 도입 문의 처리다(OPS-05). 수락·거절은 고객 조회키로만 하므로 여기 없다. */
@Profile("!migrate")
@Service
public class OpsEnterpriseLeadService {

  static final Duration QUOTE_VALIDITY = Duration.ofDays(14);
  static final Set<String> STATUSES =
      Arrays.stream(LeadStatus.values()).map(Enum::name).collect(Collectors.toUnmodifiableSet());
  private static final Set<LeadStatus> QUOTABLE =
      EnumSet.of(LeadStatus.CONSULTING, LeadStatus.QUOTED, LeadStatus.REJECTED);
  private static final Set<LeadStatus> FINISHED =
      EnumSet.of(LeadStatus.CONTRACTED, LeadStatus.CLOSED);
  private static final String TARGET = "ENTERPRISE_LEAD";

  private final EnterpriseLeadRepository leads;
  private final EnterpriseQuoteRepository quotes;
  private final EnterpriseLeadStatusHistoryRepository histories;
  private final SupportDirectoryRepository directory;
  private final OpsSupportQueryRepository queries;
  private final OperatorAccountRepository accounts;
  private final OpsAuditLogRepository auditLog;
  private final Clock clock;

  public OpsEnterpriseLeadService(
      EnterpriseLeadRepository leads,
      EnterpriseQuoteRepository quotes,
      EnterpriseLeadStatusHistoryRepository histories,
      SupportDirectoryRepository directory,
      OpsSupportQueryRepository queries,
      OperatorAccountRepository accounts,
      OpsAuditLogRepository auditLog,
      Clock clock) {
    this.leads = leads;
    this.quotes = quotes;
    this.histories = histories;
    this.directory = directory;
    this.queries = queries;
    this.accounts = accounts;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PageResponse<OpsEnterpriseLeadItem> list(
      String status, Long assignedOperatorId, Boolean unassigned, PageQuery page) {
    LeadFilter filter =
        new LeadFilter(
            OpsQueryParams.oneOf("status", status, STATUSES),
            OpsQueryParams.positiveId("assignedOperatorId", assignedOperatorId),
            Boolean.TRUE.equals(unassigned));
    if (filter.unassigned() && filter.assignedOperatorId() != null) {
      throw OpsQueryParams.invalid(
          "unassigned", FieldErrorCode.NOT_ALLOWED, "unassigned와 assignedOperatorId는 함께 쓸 수 없습니다.");
    }
    List<OpsEnterpriseLeadItem> items =
        queries.leads(filter, page.size(), (long) page.page() * page.size()).stream()
            .map(
                row ->
                    new OpsEnterpriseLeadItem(
                        row.id(),
                        row.companyName(),
                        row.status(),
                        row.assignedOperatorId(),
                        row.assignedOperatorName(),
                        row.contactName(),
                        row.branchCount(),
                        row.expectedSeats(),
                        row.migrationNeeded(),
                        row.quoteAmount(),
                        toDisplayOffset(row.quoteExpiresAt()),
                        toDisplayOffset(row.createdAt()),
                        toDisplayOffset(row.updatedAt()),
                        row.version()))
            .toList();
    return PageResponse.of(items, page.page(), page.size(), queries.countLeads(filter));
  }

  @Transactional
  public OpsEnterpriseLeadDetailResponse detail(long operatorId, long leadId) {
    EnterpriseLead lead =
        leads
            .findById(leadId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    List<EnterpriseQuote> versions = quotes.findByLeadIdOrderByVersionNoAsc(leadId);
    List<EnterpriseLeadStatusHistory> events =
        histories.findByLeadIdOrderByCreatedAtAscIdAsc(leadId);
    Set<Long> operatorIds = new HashSet<>();
    if (lead.getAssignedOperatorId() != null) {
      operatorIds.add(lead.getAssignedOperatorId());
    }
    versions.forEach(quote -> operatorIds.add(quote.getIssuedByOperatorId()));
    for (EnterpriseLeadStatusHistory event : events) {
      if (event.getActorOperatorId() != null) {
        operatorIds.add(event.getActorOperatorId());
      }
      if (event.getAssignedOperatorId() != null) {
        operatorIds.add(event.getAssignedOperatorId());
      }
    }
    Map<Long, String> names = directory.operatorNames(operatorIds);
    Instant now = clock.instant();
    auditLog.insert(operatorId, "ENTERPRISE_LEAD_VIEWED", TARGET, leadId, null, now);
    EnterpriseQuote latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
    return new OpsEnterpriseLeadDetailResponse(
        lead.getId(),
        lead.getCompanyName(),
        lead.getContactName(),
        lead.getContactPhone(),
        lead.getContactEmail(),
        lead.getBranchCount(),
        lead.getExpectedSeats(),
        lead.isMigrationNeeded(),
        lead.getContent(),
        lead.getStatus().name(),
        lead.getAssignedOperatorId(),
        names.get(lead.getAssignedOperatorId()),
        lead.getContractReference(),
        toDisplayOffset(lead.getContractedAt()),
        lead.getClosedReason(),
        lead.getClosedNote(),
        lead.getClosedFromStatus() == null ? null : lead.getClosedFromStatus().name(),
        toDisplayOffset(lead.getClosedAt()),
        versions.stream()
            .map(
                quote ->
                    new OpsEnterpriseQuote(
                        quote.getId(),
                        quote.getVersionNo(),
                        quote.getOfficeCount(),
                        quote.getSeatCount(),
                        quote.getAmount(),
                        quote.getTaxAmount(),
                        quote.getServiceScope(),
                        quote.getDeliveryDueAt(),
                        quote.getPaymentMethod(),
                        quote.getRefundTerms(),
                        toDisplayOffset(quote.getIssuedAt()),
                        names.getOrDefault(quote.getIssuedByOperatorId(), OpsDisplayNames.UNKNOWN),
                        toDisplayOffset(quote.getExpiresAt()),
                        quote.isExpiredAt(now),
                        toDisplayOffset(quote.getAcceptedAt()),
                        toDisplayOffset(quote.getRejectedAt()),
                        toDisplayOffset(quote.getSupersededAt()),
                        quote.getBillingInvoiceId()))
            .toList(),
        events.stream()
            .map(
                event ->
                    new OpsEnterpriseLeadHistory(
                        event.getEventType(),
                        event.getFromStatus() == null ? null : event.getFromStatus().name(),
                        event.getToStatus().name(),
                        event.getActorType(),
                        event.getActorOperatorId() == null
                            ? null
                            : names.getOrDefault(
                                event.getActorOperatorId(), OpsDisplayNames.UNKNOWN),
                        event.getAssignedOperatorId() == null
                            ? null
                            : names.getOrDefault(
                                event.getAssignedOperatorId(), OpsDisplayNames.UNKNOWN),
                        event.getQuoteId(),
                        event.getReason(),
                        toDisplayOffset(event.getCreatedAt())))
            .toList(),
        allowedActions(lead, latest, operatorId),
        toDisplayOffset(lead.getCreatedAt()),
        toDisplayOffset(lead.getUpdatedAt()),
        lead.getVersion());
  }

  @Transactional
  public OpsEnterpriseLeadAssignResponse assign(
      long operatorId, long leadId, String ifMatch, long assigneeId) {
    EnterpriseLead lead = lock(leadId, ifMatch);
    if (FINISHED.contains(lead.getStatus())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    requireEligible(assigneeId);
    Instant now = clock.instant();
    LeadStatus from = lead.getStatus();
    boolean first = lead.getAssignedOperatorId() == null;
    lead.assign(assigneeId, now);
    EnterpriseLead saved = leads.saveAndFlush(lead);
    histories.save(
        EnterpriseLeadStatusHistory.byOperator(
            leadId,
            first ? "ASSIGNED" : "REASSIGNED",
            from,
            saved.getStatus(),
            operatorId,
            assigneeId,
            null,
            null,
            now));
    auditLog.insert(
        operatorId, "ENTERPRISE_LEAD_ASSIGNED", TARGET, leadId, "assignee=" + assigneeId, now);
    return new OpsEnterpriseLeadAssignResponse(
        saved.getId(),
        saved.getStatus().name(),
        assigneeId,
        directory.operatorNames(List.of(assigneeId)).get(assigneeId),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public OpsEnterpriseLeadConsultationStartResponse startConsultation(
      long operatorId, long leadId, String ifMatch) {
    EnterpriseLead lead = lock(leadId, ifMatch);
    if (lead.getStatus() != LeadStatus.ASSIGNED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    requireAssignee(lead, operatorId);
    Instant now = clock.instant();
    lead.startConsultation(now);
    EnterpriseLead saved = leads.saveAndFlush(lead);
    histories.save(
        EnterpriseLeadStatusHistory.byOperator(
            leadId,
            "CONSULTATION_STARTED",
            LeadStatus.ASSIGNED,
            LeadStatus.CONSULTING,
            operatorId,
            null,
            null,
            null,
            now));
    auditLog.insert(operatorId, "ENTERPRISE_LEAD_CONSULTATION_STARTED", TARGET, leadId, null, now);
    return new OpsEnterpriseLeadConsultationStartResponse(
        saved.getId(),
        saved.getStatus().name(),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public EnterpriseLeadQuoteResponse issueQuote(
      long operatorId, long leadId, String ifMatch, OperatorEnterpriseLeadQuoteRequest body) {
    EnterpriseLead lead = lock(leadId, ifMatch);
    if (!QUOTABLE.contains(lead.getStatus())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    requireAssignee(lead, operatorId);
    Instant now = clock.instant();
    EnterpriseQuote previous = quotes.findFirstByLeadIdOrderByVersionNoDesc(leadId).orElse(null);
    Long supersededId = null;
    if (previous != null && !previous.isSuperseded()) {
      previous.supersede(now);
      quotes.saveAndFlush(previous);
      supersededId = previous.getId();
    }
    Instant expiresAt = now.plus(QUOTE_VALIDITY);
    EnterpriseQuote quote =
        quotes.saveAndFlush(
            EnterpriseQuote.issue(
                leadId,
                previous == null ? 1 : previous.getVersionNo() + 1,
                new EnterpriseQuote.Terms(
                    body.officeCount(),
                    body.seatCount(),
                    body.quoteAmount(),
                    body.serviceScope().strip(),
                    body.taxAmount(),
                    body.deliveryDueAt(),
                    body.paymentMethod().strip(),
                    body.refundTerms().strip()),
                operatorId,
                expiresAt,
                now));
    LeadStatus from = lead.getStatus();
    lead.quoted(quote.getAmount(), quote.getExpiresAt(), now);
    EnterpriseLead saved = leads.saveAndFlush(lead);
    histories.save(
        EnterpriseLeadStatusHistory.byOperator(
            leadId,
            "QUOTE_ISSUED",
            from,
            LeadStatus.QUOTED,
            operatorId,
            null,
            quote.getId(),
            null,
            now));
    auditLog.insert(
        operatorId,
        "ENTERPRISE_QUOTE_ISSUED",
        TARGET,
        leadId,
        "quoteId=" + quote.getId() + " versionNo=" + quote.getVersionNo(),
        now);
    return new EnterpriseLeadQuoteResponse(
        quote.getId(),
        quote.getVersionNo(),
        toDisplayOffset(quote.getExpiresAt()),
        leadId,
        saved.getStatus().name(),
        supersededId,
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public OpsEnterpriseLeadContractResponse contract(
      long operatorId, long leadId, String ifMatch, OpsEnterpriseLeadContractRequest body) {
    Instant now = clock.instant();
    Instant contractedAt = body.contractedAt().toInstant();
    if (contractedAt.isAfter(now)) {
      throw OpsQueryParams.invalid(
          "contractedAt", FieldErrorCode.OUT_OF_RANGE, "계약 체결 일시는 현재 시각 이전이어야 합니다.");
    }
    EnterpriseLead lead = lock(leadId, ifMatch);
    if (lead.getStatus() != LeadStatus.ACCEPTED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    requireAssignee(lead, operatorId);
    EnterpriseQuote latest = quotes.findFirstByLeadIdOrderByVersionNoDesc(leadId).orElse(null);
    if (latest == null || latest.isSuperseded() || latest.getAcceptedAt() == null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    String reference = body.contractReference().strip();
    lead.contract(reference, contractedAt, now);
    EnterpriseLead saved = leads.saveAndFlush(lead);
    histories.save(
        EnterpriseLeadStatusHistory.byOperator(
            leadId,
            "CONTRACTED",
            LeadStatus.ACCEPTED,
            LeadStatus.CONTRACTED,
            operatorId,
            null,
            null,
            reference,
            now));
    auditLog.insert(
        operatorId, "ENTERPRISE_LEAD_CONTRACTED", TARGET, leadId, "quoteId=" + latest.getId(), now);
    return new OpsEnterpriseLeadContractResponse(
        saved.getId(),
        saved.getStatus().name(),
        saved.getContractReference(),
        toDisplayOffset(saved.getContractedAt()),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public OpsEnterpriseLeadCloseResponse close(
      long operatorId, long leadId, String ifMatch, OpsEnterpriseLeadCloseRequest body) {
    String note =
        body.closedNote() == null || body.closedNote().isBlank() ? null : body.closedNote().strip();
    if (body.closedReason().equals("OTHER") && note == null) {
      throw OpsQueryParams.invalid("closedNote", FieldErrorCode.REQUIRED, "기타 사유로 종료하면 설명이 필요합니다.");
    }
    EnterpriseLead lead = lock(leadId, ifMatch);
    if (FINISHED.contains(lead.getStatus())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    if (lead.getAssignedOperatorId() != null) {
      requireAssignee(lead, operatorId);
    }
    Instant now = clock.instant();
    LeadStatus from = lead.getStatus();
    lead.close(body.closedReason(), note, now);
    EnterpriseLead saved = leads.saveAndFlush(lead);
    histories.save(
        EnterpriseLeadStatusHistory.byOperator(
            leadId,
            "CLOSED",
            from,
            LeadStatus.CLOSED,
            operatorId,
            null,
            null,
            note == null ? body.closedReason() : body.closedReason() + ": " + note,
            now));
    auditLog.insert(
        operatorId, "ENTERPRISE_LEAD_CLOSED", TARGET, leadId, "reason=" + body.closedReason(), now);
    return new OpsEnterpriseLeadCloseResponse(
        saved.getId(),
        saved.getStatus().name(),
        saved.getClosedReason(),
        saved.getClosedFromStatus().name(),
        toDisplayOffset(saved.getClosedAt()),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  @Transactional
  public OpsEnterpriseLeadReopenResponse reopen(
      long operatorId, long leadId, String ifMatch, OpsEnterpriseLeadReopenRequest body) {
    EnterpriseLead lead = lock(leadId, ifMatch);
    if (lead.getStatus() != LeadStatus.CLOSED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    LeadStatus target = reopenTarget(lead.getClosedFromStatus());
    Long previousAssignee = lead.getAssignedOperatorId();
    Long requested = body.assignedOperatorId();
    if (requested != null) {
      requireEligible(requested);
      if (target == LeadStatus.RECEIVED) {
        target = LeadStatus.ASSIGNED;
      }
    }
    Long assignee = requested != null ? requested : previousAssignee;
    if (target != LeadStatus.RECEIVED
        && (assignee == null || !accounts.isActiveWithRole(assignee, OperatorRole.SUPPORT))) {
      throw new BusinessException(ErrorCode.ASSIGNEE_REQUIRED);
    }
    Instant now = clock.instant();
    String reason = body.reason().strip();
    lead.reopen(target, assignee, now);
    EnterpriseLead saved = leads.saveAndFlush(lead);
    histories.save(
        EnterpriseLeadStatusHistory.byOperator(
            leadId, "REOPENED", LeadStatus.CLOSED, target, operatorId, null, null, reason, now));
    if (requested != null && !requested.equals(previousAssignee)) {
      histories.save(
          EnterpriseLeadStatusHistory.byOperator(
              leadId,
              previousAssignee == null ? "ASSIGNED" : "REASSIGNED",
              target,
              target,
              operatorId,
              requested,
              null,
              null,
              now));
    }
    auditLog.insert(
        operatorId, "ENTERPRISE_LEAD_REOPENED", TARGET, leadId, "status=" + target, now);
    return new OpsEnterpriseLeadReopenResponse(
        saved.getId(),
        saved.getStatus().name(),
        saved.getAssignedOperatorId(),
        toDisplayOffset(saved.getUpdatedAt()),
        saved.getVersion());
  }

  /** 견적·수락·거절 단계에서 종료했으면 상담으로, 그 밖에는 종료 직전 단계로 돌아간다. */
  static LeadStatus reopenTarget(LeadStatus closedFrom) {
    if (closedFrom == null) {
      return LeadStatus.RECEIVED;
    }
    return switch (closedFrom) {
      case QUOTED, ACCEPTED, REJECTED -> LeadStatus.CONSULTING;
      case RECEIVED, ASSIGNED, CONSULTING -> closedFrom;
      case CONTRACTED, CLOSED -> throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    };
  }

  private List<String> allowedActions(EnterpriseLead lead, EnterpriseQuote latest, long me) {
    List<String> actions = new ArrayList<>();
    LeadStatus status = lead.getStatus();
    boolean assignee = Objects.equals(lead.getAssignedOperatorId(), me);
    if (!FINISHED.contains(status)) {
      actions.add("ASSIGN");
    }
    if (status == LeadStatus.ASSIGNED && assignee) {
      actions.add("START_CONSULTATION");
    }
    if (QUOTABLE.contains(status) && assignee) {
      actions.add("ISSUE_QUOTE");
    }
    if (status == LeadStatus.ACCEPTED
        && assignee
        && latest != null
        && !latest.isSuperseded()
        && latest.getAcceptedAt() != null) {
      actions.add("CONTRACT");
    }
    if (!FINISHED.contains(status) && (lead.getAssignedOperatorId() == null || assignee)) {
      actions.add("CLOSE");
    }
    if (status == LeadStatus.CLOSED) {
      actions.add("REOPEN");
    }
    return actions;
  }

  private EnterpriseLead lock(long leadId, String ifMatch) {
    long expected = IfMatchVersion.parse(ifMatch);
    EnterpriseLead lead =
        leads
            .findForUpdateById(leadId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!Objects.equals(lead.getVersion(), expected)) {
      throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    return lead;
  }

  /** 배정 담당자 본인이고 DB 기준으로 지금도 활성 고객지원 운영자여야 한다. */
  private void requireAssignee(EnterpriseLead lead, long operatorId) {
    if (!Objects.equals(lead.getAssignedOperatorId(), operatorId)
        || !accounts.isActiveWithRole(operatorId, OperatorRole.SUPPORT)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private void requireEligible(long operatorId) {
    if (!accounts.isActiveWithRole(operatorId, OperatorRole.SUPPORT)) {
      throw new BusinessException(ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    }
  }
}
