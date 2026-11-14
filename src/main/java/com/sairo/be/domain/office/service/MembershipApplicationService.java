package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.request.MembershipCreateRequest;
import com.sairo.be.domain.office.dto.response.MembershipInvitationAcceptResponse;
import com.sairo.be.domain.office.dto.response.MembershipResultResponse;
import com.sairo.be.domain.office.dto.response.MembershipStatusResponse;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.entity.OfficeRequestSource;
import com.sairo.be.domain.office.mapper.MembershipMapper;
import com.sairo.be.domain.office.repository.MembershipRecordRepository;
import com.sairo.be.domain.office.repository.MembershipRecordRepository.Row;
import com.sairo.be.domain.office.repository.OfficeMembershipHistoryRepository;
import com.sairo.be.domain.office.repository.OfficeMembershipHistoryRepository.Actor;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.TimeRules;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class MembershipApplicationService {

  private final MembershipRecordRepository repository;
  private final OfficeMembershipHistoryRepository historyRepository;
  private final JdbcClient jdbcClient;
  private final AdvisoryLock advisoryLock;
  private final Clock clock;

  public MembershipApplicationService(
      MembershipRecordRepository repository,
      OfficeMembershipHistoryRepository historyRepository,
      JdbcClient jdbcClient,
      AdvisoryLock advisoryLock,
      Clock clock) {
    this.repository = repository;
    this.historyRepository = historyRepository;
    this.jdbcClient = jdbcClient;
    this.advisoryLock = advisoryLock;
    this.clock = clock;
  }

  @Transactional
  public MembershipResultResponse create(
      Long userId, Long officeId, MembershipCreateRequest request) {
    OfficeMembershipRole role = MembershipRules.requestedRole(request.role());
    MembershipRules.requireQualification(
        role,
        request.qualificationNumber(),
        request.employmentReportConfirmed(),
        request.trainingConfirmed());
    requireVerifiedOffice(officeId);
    return open(userId, officeId, role, request);
  }

  @Transactional(readOnly = true)
  public MembershipStatusResponse latest(Long userId) {
    Row row =
        repository
            .latestOfUser(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    String officeName =
        jdbcClient
            .sql("SELECT name FROM office WHERE id = :id")
            .param("id", row.officeId())
            .query(String.class)
            .single();
    Instant now = clock.instant();
    boolean pending = row.status() == OfficeMembershipStatus.PENDING;
    boolean rejected = row.status() == OfficeMembershipStatus.REJECTED;
    boolean expired = pending && MembershipRules.isExpiredPending(row, now);
    return new MembershipStatusResponse(
        row.id(),
        row.officeId(),
        officeName,
        row.role().name(),
        expired ? OfficeMembershipStatus.EXPIRED.name() : row.status().name(),
        rejected ? row.rejectionReason() : null,
        rejected ? row.resolvedAt() != null : null,
        row.requestSource().name(),
        pending && !expired ? TimeRules.toDisplayOffset(MembershipRules.expiresAt(row)) : null,
        TimeRules.toDisplayOffset(row.invitationAcceptedAt()),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }

  @Transactional
  public MembershipResultResponse cancel(Long userId, Long membershipId) {
    Row row = lockOwned(userId, membershipId);
    Instant now = clock.instant();
    MembershipRules.requireLivePending(row, now);
    repository.cancel(membershipId, now);
    historyRepository.insert(
        membershipId,
        row.status(),
        OfficeMembershipStatus.CANCELLED,
        Actor.APPLICANT,
        null,
        null,
        now);
    return MembershipMapper.toResult(repository.find(membershipId).orElseThrow());
  }

  @Transactional
  public MembershipResultResponse startReapplication(
      Long userId, Long membershipId, MembershipCreateRequest request) {
    Row row = lockOwned(userId, membershipId);
    if (row.status() != OfficeMembershipStatus.REJECTED || row.resolvedAt() != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    OfficeMembershipRole role = MembershipRules.requestedRole(request.role());
    MembershipRules.requireQualification(
        role,
        request.qualificationNumber(),
        request.employmentReportConfirmed(),
        request.trainingConfirmed());
    requireVerifiedOffice(row.officeId());
    repository.resolve(membershipId, clock.instant());
    return open(userId, row.officeId(), role, request);
  }

  @Transactional
  public MembershipInvitationAcceptResponse acceptInvitation(
      Long userId, Long membershipId, MembershipCreateRequest request) {
    Row row = lockOwned(userId, membershipId);
    Instant now = clock.instant();
    if (row.requestSource() != OfficeRequestSource.INVITE || row.invitationAcceptedAt() != null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    MembershipRules.requireLivePending(row, now);
    MembershipRules.requireQualification(
        row.role(),
        request.qualificationNumber(),
        request.employmentReportConfirmed(),
        request.trainingConfirmed());
    repository.acceptInvitation(
        membershipId,
        MembershipRules.normalizedNumber(row.role(), request.qualificationNumber()),
        true,
        true,
        now);
    Row updated = repository.find(membershipId).orElseThrow();
    return new MembershipInvitationAcceptResponse(
        membershipId,
        TimeRules.toDisplayOffset(updated.invitationAcceptedAt()),
        updated.officeId(),
        TimeRules.toDisplayOffset(updated.createdAt()),
        TimeRules.toDisplayOffset(updated.updatedAt()),
        updated.version());
  }

  private MembershipResultResponse open(
      Long userId, Long officeId, OfficeMembershipRole role, MembershipCreateRequest request) {
    advisoryLock.lockForTransaction("membership:user:" + userId);
    List<String> open =
        jdbcClient
            .sql(
                "SELECT status FROM office_membership WHERE user_id = :user AND status IN"
                    + " ('APPROVED','PENDING','SUSPENDED')")
            .param("user", userId)
            .query(String.class)
            .list();
    if (!open.isEmpty()) {
      throw new BusinessException(ErrorCode.PENDING_MEMBERSHIP_EXISTS);
    }
    Instant now = clock.instant();
    long id;
    try {
      id =
          repository.insertPending(
              officeId,
              userId,
              role,
              OfficeRequestSource.REQUEST,
              null,
              MembershipRules.normalizedNumber(role, request.qualificationNumber()),
              true,
              true,
              now);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.PENDING_MEMBERSHIP_EXISTS);
    }
    historyRepository.insert(
        id, null, OfficeMembershipStatus.PENDING, Actor.APPLICANT, null, null, now);
    return MembershipMapper.toResult(repository.find(id).orElseThrow());
  }

  private void requireVerifiedOffice(Long officeId) {
    Long found =
        jdbcClient
            .sql(
                "SELECT count(*) FROM office_registration r JOIN office o ON o.id ="
                    + " r.resulting_office_id WHERE r.resulting_office_id = :id AND r.status ="
                    + " 'APPROVED' AND o.closed_at IS NULL")
            .param("id", officeId)
            .query(Long.class)
            .single();
    if (found == 0) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  private Row lockOwned(Long userId, Long membershipId) {
    Row row =
        repository
            .lock(membershipId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (row.userId() != userId) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return row;
  }
}
