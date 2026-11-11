package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.response.MembershipResultResponse;
import com.sairo.be.domain.office.dto.response.MembershipRoleUpdateResponse;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.office.entity.OfficeMembershipStatus;
import com.sairo.be.domain.office.mapper.MembershipMapper;
import com.sairo.be.domain.office.repository.MembershipRecordRepository;
import com.sairo.be.domain.office.repository.MembershipRecordRepository.Row;
import com.sairo.be.domain.office.repository.OfficeMembershipHistoryRepository;
import com.sairo.be.domain.office.repository.OfficeMembershipHistoryRepository.Actor;
import com.sairo.be.global.error.BusinessException;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.error.FieldErrorCode;
import com.sairo.be.global.port.ContractAssignments;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.StepUpState;
import com.sairo.be.global.support.AdvisoryLock;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class MembershipAdminService {

  private final MembershipRecordRepository repository;
  private final OfficeMembershipHistoryRepository historyRepository;
  private final MemberSessionRefreshService sessionRefreshService;
  private final StepUpState stepUpState;
  private final AdvisoryLock advisoryLock;
  private final ContractAssignments contractAssignments;
  private final Clock clock;

  public MembershipAdminService(
      MembershipRecordRepository repository,
      OfficeMembershipHistoryRepository historyRepository,
      MemberSessionRefreshService sessionRefreshService,
      StepUpState stepUpState,
      AdvisoryLock advisoryLock,
      ContractAssignments contractAssignments,
      Clock clock) {
    this.repository = repository;
    this.historyRepository = historyRepository;
    this.sessionRefreshService = sessionRefreshService;
    this.stepUpState = stepUpState;
    this.advisoryLock = advisoryLock;
    this.contractAssignments = contractAssignments;
    this.clock = clock;
  }

  @Transactional
  public MembershipRoleUpdateResponse changeRole(
      StaffPrincipal owner,
      HttpSession session,
      Long membershipId,
      long expectedVersion,
      OfficeMembershipRole role) {
    if (role == OfficeMembershipRole.OWNER) {
      throw MembershipRules.invalid("role", FieldErrorCode.NOT_ALLOWED, "대표 변경은 대표 이전 절차를 써야 합니다.");
    }
    stepUpState.require(session);
    Row row = lockInOffice(owner, membershipId);
    if (row.version() != expectedVersion) {
      throw BusinessException.versionConflict(row.version());
    }
    if (row.role() == OfficeMembershipRole.OWNER) {
      throw MembershipRules.invalid("role", FieldErrorCode.NOT_ALLOWED, "대표의 역할은 바꿀 수 없습니다.");
    }
    if (row.status() != OfficeMembershipStatus.APPROVED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    repository.changeRole(membershipId, role, now);
    if (role == OfficeMembershipRole.DEPUTY) {
      contractAssignments.handOverToOwner(owner.officeId(), membershipId, now);
    }
    stepUpState.consume(session);
    sessionRefreshService.refreshAfterCommit(row.userId());
    return MembershipMapper.toRoleResult(repository.find(membershipId).orElseThrow());
  }

  @Transactional
  public MembershipResultResponse suspend(StaffPrincipal owner, Long membershipId, String reason) {
    Row row = lockInOffice(owner, membershipId);
    requireNotLastOwner(row);
    if (row.status() != OfficeMembershipStatus.APPROVED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    repository.suspend(membershipId, reason.strip(), now);
    contractAssignments.handOverToOwner(owner.officeId(), membershipId, now);
    historyRepository.insert(
        membershipId,
        OfficeMembershipStatus.APPROVED,
        OfficeMembershipStatus.SUSPENDED,
        Actor.OWNER,
        owner.membershipId(),
        reason.strip(),
        now);
    sessionRefreshService.terminateAfterCommit(List.of(row.userId()));
    return MembershipMapper.toResult(repository.find(membershipId).orElseThrow());
  }

  @Transactional
  public MembershipResultResponse reinstate(StaffPrincipal owner, Long membershipId) {
    Row row = lockInOffice(owner, membershipId);
    if (row.status() != OfficeMembershipStatus.SUSPENDED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    try {
      repository.approve(membershipId, now);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    historyRepository.insert(
        membershipId,
        OfficeMembershipStatus.SUSPENDED,
        OfficeMembershipStatus.APPROVED,
        Actor.OWNER,
        owner.membershipId(),
        null,
        now);
    return MembershipMapper.toResult(repository.find(membershipId).orElseThrow());
  }

  @Transactional
  public void revoke(StaffPrincipal owner, HttpSession session, Long membershipId, String reason) {
    stepUpState.require(session);
    Row row = lockInOffice(owner, membershipId);
    requireNotLastOwner(row);
    if (row.status() != OfficeMembershipStatus.APPROVED
        && row.status() != OfficeMembershipStatus.SUSPENDED) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    Instant now = clock.instant();
    repository.revoke(membershipId, "OWNER", reason.strip(), now);
    contractAssignments.handOverToOwner(owner.officeId(), membershipId, now);
    historyRepository.insert(
        membershipId,
        row.status(),
        OfficeMembershipStatus.REVOKED,
        Actor.OWNER,
        owner.membershipId(),
        reason.strip(),
        now);
    stepUpState.consume(session);
    sessionRefreshService.terminateAfterCommit(List.of(row.userId()));
  }

  @Transactional
  public void leave(StaffPrincipal principal) {
    advisoryLock.lockForTransaction("office-owner:" + principal.officeId());
    Row row =
        repository
            .lock(principal.membershipId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    if (row.status() != OfficeMembershipStatus.APPROVED) {
      throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    requireNotLastOwner(row);
    Instant now = clock.instant();
    repository.revoke(row.id(), "SELF", null, now);
    contractAssignments.handOverToOwner(principal.officeId(), row.id(), now);
    historyRepository.insert(
        row.id(),
        OfficeMembershipStatus.APPROVED,
        OfficeMembershipStatus.REVOKED,
        Actor.APPLICANT,
        null,
        null,
        now);
    sessionRefreshService.refreshAfterCommit(row.userId());
  }

  private void requireNotLastOwner(Row row) {
    if (row.role() == OfficeMembershipRole.OWNER) {
      throw new BusinessException(ErrorCode.LAST_OWNER);
    }
  }

  private Row lockInOffice(StaffPrincipal owner, Long membershipId) {
    return repository
        .lock(membershipId)
        .filter(found -> found.officeId() == owner.officeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }
}
