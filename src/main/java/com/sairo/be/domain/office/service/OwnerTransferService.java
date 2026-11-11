package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.request.OwnerTransferCreateRequest;
import com.sairo.be.domain.office.dto.response.OwnerTransferResponse;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.repository.MembershipRecordRepository;
import com.sairo.be.domain.office.repository.MembershipRecordRepository.Row;
import com.sairo.be.domain.office.repository.OwnerTransferRepository;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class OwnerTransferService {

  private final OwnerTransferRepository transferRepository;
  private final MembershipRecordRepository membershipRepository;
  private final MemberSessionRefreshService sessionRefreshService;
  private final StepUpState stepUpState;
  private final AdvisoryLock advisoryLock;
  private final JdbcClient jdbcClient;
  private final Clock clock;

  public OwnerTransferService(
      OwnerTransferRepository transferRepository,
      MembershipRecordRepository membershipRepository,
      MemberSessionRefreshService sessionRefreshService,
      StepUpState stepUpState,
      AdvisoryLock advisoryLock,
      JdbcClient jdbcClient,
      Clock clock) {
    this.transferRepository = transferRepository;
    this.membershipRepository = membershipRepository;
    this.sessionRefreshService = sessionRefreshService;
    this.stepUpState = stepUpState;
    this.advisoryLock = advisoryLock;
    this.jdbcClient = jdbcClient;
    this.clock = clock;
  }

  @Transactional
  public OwnerTransferResponse create(
      StaffPrincipal owner, HttpSession session, OwnerTransferCreateRequest request) {
    stepUpState.require(session);
    advisoryLock.lockForTransaction("owner-transfer:" + owner.officeId());
    Row target =
        jdbcClient
            .sql(
                "SELECT id FROM office_membership WHERE office_id = :office AND user_id = :user"
                    + " AND status = 'APPROVED'")
            .param("office", owner.officeId())
            .param("user", request.toUserId())
            .query(Long.class)
            .optional()
            .flatMap(membershipRepository::find)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (target.role() != OfficeMembershipRole.AGENT) {
      throw MembershipRules.invalid(
          "toUserId", FieldErrorCode.NOT_ALLOWED, "소속공인중개사만 신규 대표가 될 수 있습니다.");
    }
    if (transferRepository.inProgress(owner.officeId()).isPresent()) {
      throw new BusinessException(ErrorCode.TRANSFER_IN_PROGRESS);
    }
    long id;
    try {
      id =
          transferRepository.insert(
              owner.officeId(),
              owner.membershipId(),
              target.id(),
              clock.instant().truncatedTo(ChronoUnit.MICROS));
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.TRANSFER_IN_PROGRESS);
    }
    stepUpState.consume(session);
    return response(transferRepository.find(id).orElseThrow());
  }

  @Transactional(readOnly = true)
  public OwnerTransferResponse get(StaffPrincipal principal, Long transferId) {
    var row =
        transferRepository
            .find(transferId)
            .filter(found -> found.officeId() == principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    requireParty(principal, row);
    return response(row);
  }

  @Transactional(readOnly = true)
  public OwnerTransferResponse current(StaffPrincipal principal) {
    var row =
        transferRepository
            .inProgress(principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    requireParty(principal, row);
    return response(row);
  }

  @Transactional
  public OwnerTransferResponse accept(StaffPrincipal principal, Long transferId) {
    var row = lockLive(principal, transferId);
    requireCandidate(principal, row);
    transferRepository.markAccepted(transferId, clock.instant());
    return response(transferRepository.find(transferId).orElseThrow());
  }

  @Transactional
  public OwnerTransferResponse reject(StaffPrincipal principal, Long transferId, String reason) {
    var row = lockLive(principal, transferId);
    requireCandidate(principal, row);
    transferRepository.markRejected(
        transferId, reason == null || reason.isBlank() ? null : reason.strip(), clock.instant());
    return response(transferRepository.find(transferId).orElseThrow());
  }

  @Transactional
  public OwnerTransferResponse withdraw(StaffPrincipal principal, Long transferId) {
    var row =
        transferRepository
            .lock(transferId)
            .filter(found -> found.officeId() == principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (row.fromMembershipId() != principal.membershipId()) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (!row.status().equals("PENDING") && !row.status().equals("UNDER_REVIEW")) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    transferRepository.markWithdrawn(transferId, clock.instant());
    return response(transferRepository.find(transferId).orElseThrow());
  }

  @Transactional
  public OwnerTransferResponse complete(Long transferId) {
    var row =
        transferRepository
            .lock(transferId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    advisoryLock.lockForTransaction("owner-transfer:" + row.officeId());
    if (!row.status().equals("UNDER_REVIEW")) {
      throw new BusinessException(ErrorCode.TRANSFER_PRECONDITION_FAILED);
    }
    Row from = membershipRepository.lock(row.fromMembershipId()).orElse(null);
    Row to = membershipRepository.lock(row.toMembershipId()).orElse(null);
    boolean officeActive =
        jdbcClient
                .sql(
                    "SELECT count(*) FROM office_registration WHERE resulting_office_id = :id"
                        + " AND status = 'APPROVED'")
                .param("id", row.officeId())
                .query(Long.class)
                .single()
            > 0;
    if (from == null
        || to == null
        || !officeActive
        || from.status() != OfficeMembershipStatus.APPROVED
        || from.role() != OfficeMembershipRole.OWNER
        || to.status() != OfficeMembershipStatus.APPROVED
        || to.role() != OfficeMembershipRole.AGENT) {
      throw new BusinessException(ErrorCode.TRANSFER_PRECONDITION_FAILED);
    }
    Instant now = clock.instant();
    membershipRepository.changeRole(from.id(), OfficeMembershipRole.AGENT, now);
    membershipRepository.changeRole(to.id(), OfficeMembershipRole.OWNER, now);
    transferRepository.markCompleted(transferId, now);
    sessionRefreshService.refreshAfterCommit(from.userId());
    sessionRefreshService.refreshAfterCommit(to.userId());
    return response(transferRepository.find(transferId).orElseThrow());
  }

  @Transactional
  public int expireOverdue() {
    int expired = 0;
    for (var row : transferRepository.overdue(clock.instant())) {
      transferRepository.markExpired(row.id());
      expired++;
    }
    return expired;
  }

  private OwnerTransferRepository.Row lockLive(StaffPrincipal principal, Long transferId) {
    var row =
        transferRepository
            .lock(transferId)
            .filter(found -> found.officeId() == principal.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!row.status().equals("PENDING") || !row.acceptDeadlineAt().isAfter(clock.instant())) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    return row;
  }

  private void requireCandidate(StaffPrincipal principal, OwnerTransferRepository.Row row) {
    if (row.toMembershipId() != principal.membershipId()) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private void requireParty(StaffPrincipal principal, OwnerTransferRepository.Row row) {
    if (row.toMembershipId() != principal.membershipId()
        && row.fromMembershipId() != principal.membershipId()) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private OwnerTransferResponse response(OwnerTransferRepository.Row row) {
    return new OwnerTransferResponse(
        row.id(),
        row.officeId(),
        displayName(row.fromMembershipId()),
        displayName(row.toMembershipId()),
        row.status(),
        TimeRules.toDisplayOffset(row.requestedAt()),
        TimeRules.toDisplayOffset(row.acceptDeadlineAt()),
        TimeRules.toDisplayOffset(row.acceptedAt()),
        TimeRules.toDisplayOffset(row.completedAt()),
        TimeRules.toDisplayOffset(row.withdrawnAt()),
        row.rejectedReason(),
        TimeRules.toDisplayOffset(row.rejectedAt()));
  }

  private String displayName(long membershipId) {
    return jdbcClient
        .sql(
            "SELECT m.withdrawn_alias_no, u.name, u.account_status FROM office_membership m JOIN"
                + " app_user u ON u.id = m.user_id WHERE m.id = :id")
        .param("id", membershipId)
        .query(
            (rs, i) ->
                WithdrawnMemberDisplay.name(
                    "WITHDRAWN".equals(rs.getString("account_status")),
                    rs.getString("name"),
                    (Integer) rs.getObject("withdrawn_alias_no")))
        .single();
  }
}
