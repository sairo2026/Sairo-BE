package com.sairo.be.domain.office.service;

import com.sairo.be.domain.office.dto.request.MembershipInviteRequest;
import com.sairo.be.domain.office.dto.response.MembershipDetailResponse;
import com.sairo.be.domain.office.dto.response.MembershipInvitationResponse;
import com.sairo.be.domain.office.dto.response.MembershipResultResponse;
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
import com.sairo.be.global.port.ContractAssignments;
import com.sairo.be.global.port.MailSender;
import com.sairo.be.global.port.MailSender.MailMessage;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.support.AdvisoryLock;
import com.sairo.be.global.support.AfterCommit;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Profile("!migrate")
@Service
public class MembershipDecisionService {

  private final MembershipRecordRepository repository;
  private final OfficeMembershipHistoryRepository historyRepository;
  private final DeputyQuotaService quotaService;
  private final MemberSessionRefreshService sessionRefreshService;
  private final JdbcClient jdbcClient;
  private final AdvisoryLock advisoryLock;
  private final MailSender mailSender;
  private final ContractAssignments contractAssignments;
  private final Clock clock;
  private final String frontendBaseUrl;

  public MembershipDecisionService(
      MembershipRecordRepository repository,
      OfficeMembershipHistoryRepository historyRepository,
      DeputyQuotaService quotaService,
      MemberSessionRefreshService sessionRefreshService,
      JdbcClient jdbcClient,
      AdvisoryLock advisoryLock,
      MailSender mailSender,
      Clock clock,
      ContractAssignments contractAssignments,
      @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
    this.repository = repository;
    this.historyRepository = historyRepository;
    this.quotaService = quotaService;
    this.sessionRefreshService = sessionRefreshService;
    this.jdbcClient = jdbcClient;
    this.advisoryLock = advisoryLock;
    this.mailSender = mailSender;
    this.clock = clock;
    this.contractAssignments = contractAssignments;
    this.frontendBaseUrl = frontendBaseUrl;
  }

  @Transactional
  public MembershipResultResponse approve(StaffPrincipal owner, Long membershipId) {
    advisoryLock.lockForTransaction("membership-quota:" + owner.officeId());
    Row row = lockInOffice(owner, membershipId);
    Instant now = clock.instant();
    requireDecidable(row, now);
    if (row.role() == OfficeMembershipRole.DEPUTY
        && quotaService.wouldExceedWithAdditionalDeputy(owner.officeId())) {
      throw new BusinessException(ErrorCode.DEPUTY_QUOTA_EXCEEDED);
    }
    try {
      repository.approve(membershipId, now);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
    historyRepository.insert(
        membershipId,
        OfficeMembershipStatus.PENDING,
        OfficeMembershipStatus.APPROVED,
        Actor.OWNER,
        owner.membershipId(),
        null,
        now);
    sessionRefreshService.refreshAfterCommit(row.userId());
    return MembershipMapper.toResult(repository.find(membershipId).orElseThrow());
  }

  @Transactional
  public MembershipResultResponse reject(StaffPrincipal owner, Long membershipId, String reason) {
    Row row = lockInOffice(owner, membershipId);
    Instant now = clock.instant();
    requireDecidable(row, now);
    repository.reject(membershipId, reason.strip(), now);
    historyRepository.insert(
        membershipId,
        OfficeMembershipStatus.PENDING,
        OfficeMembershipStatus.REJECTED,
        Actor.OWNER,
        owner.membershipId(),
        reason.strip(),
        now);
    return MembershipMapper.toResult(repository.find(membershipId).orElseThrow());
  }

  @Transactional
  public MembershipInvitationResponse invite(
      StaffPrincipal owner, MembershipInviteRequest request) {
    OfficeMembershipRole role = MembershipRules.requestedRole(request.role());
    Long targetUserId =
        jdbcClient
            .sql(
                "SELECT id FROM app_user WHERE lower(email) = lower(:email) AND account_status ="
                    + " 'ACTIVE'")
            .param("email", request.email().strip())
            .query(Long.class)
            .optional()
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    advisoryLock.lockForTransaction("membership:user:" + targetUserId);
    Long open =
        jdbcClient
            .sql(
                "SELECT count(*) FROM office_membership WHERE user_id = :user AND status IN"
                    + " ('APPROVED','PENDING','SUSPENDED')")
            .param("user", targetUserId)
            .query(Long.class)
            .single();
    if (open > 0) {
      throw new BusinessException(ErrorCode.PENDING_MEMBERSHIP_EXISTS);
    }
    Instant now = clock.instant();
    long id;
    try {
      id =
          repository.insertPending(
              owner.officeId(),
              targetUserId,
              role,
              OfficeRequestSource.INVITE,
              now.plus(MembershipRules.INVITATION_VALIDITY),
              null,
              null,
              null,
              now);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(ErrorCode.PENDING_MEMBERSHIP_EXISTS);
    }
    historyRepository.insert(
        id, null, OfficeMembershipStatus.PENDING, Actor.OWNER, owner.membershipId(), null, now);
    sendInvitationMail(request.email().strip(), owner.officeId());
    return MembershipMapper.toInvitation(repository.find(id).orElseThrow());
  }

  @Transactional
  public MembershipResultResponse cancelInvitation(StaffPrincipal owner, Long membershipId) {
    Row row = lockInOffice(owner, membershipId);
    requireInvitePending(row);
    Instant now = clock.instant();
    repository.cancel(membershipId, now);
    historyRepository.insert(
        membershipId,
        OfficeMembershipStatus.PENDING,
        OfficeMembershipStatus.CANCELLED,
        Actor.OWNER,
        owner.membershipId(),
        null,
        now);
    return MembershipMapper.toResult(repository.find(membershipId).orElseThrow());
  }

  @Transactional
  public MembershipInvitationResponse resendInvitation(StaffPrincipal owner, Long membershipId) {
    Row row = lockInOffice(owner, membershipId);
    requireInvitePending(row);
    Instant now = clock.instant();
    repository.extendInvitation(membershipId, now.plus(MembershipRules.INVITATION_VALIDITY), now);
    String email =
        jdbcClient
            .sql("SELECT email FROM app_user WHERE id = :id")
            .param("id", row.userId())
            .query(String.class)
            .optional()
            .orElse(null);
    if (email != null) {
      sendInvitationMail(email, owner.officeId());
    }
    return MembershipMapper.toInvitation(repository.find(membershipId).orElseThrow());
  }

  @Transactional(readOnly = true)
  public MembershipDetailResponse detail(StaffPrincipal owner, Long membershipId) {
    Row row =
        repository
            .find(membershipId)
            .filter(found -> found.officeId() == owner.officeId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    var user =
        jdbcClient
            .sql("SELECT name, account_status FROM app_user WHERE id = :id")
            .param("id", row.userId())
            .query((rs, i) -> new String[] {rs.getString("name"), rs.getString("account_status")})
            .single();
    int aliasNumber =
        jdbcClient
            .sql("SELECT COALESCE(withdrawn_alias_no, 0) FROM office_membership WHERE id = :id")
            .param("id", membershipId)
            .query(Integer.class)
            .single();
    Integer alias = aliasNumber == 0 ? null : aliasNumber;
    long customers = count("customer", membershipId);
    long properties = count("property", membershipId);
    long contracts = contractAssignments.countOpen(row.officeId(), membershipId);
    return new MembershipDetailResponse(
        row.id(),
        row.officeId(),
        row.userId(),
        WithdrawnMemberDisplay.name("WITHDRAWN".equals(user[1]), user[0], alias),
        row.role().name(),
        row.status().name(),
        row.requestSource().name(),
        row.qualificationNumber(),
        row.employmentReportConfirmed(),
        row.trainingConfirmed(),
        row.status() == OfficeMembershipStatus.APPROVED,
        TimeRules.toDisplayOffset(row.approvedAt()),
        new MembershipDetailResponse.AssignedCounts(customers, properties, 0, contracts, 0),
        TimeRules.toDisplayOffset(row.createdAt()),
        TimeRules.toDisplayOffset(row.updatedAt()),
        row.version());
  }

  private long count(String table, long membershipId) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM "
                + table
                + " WHERE managing_membership_id = :id AND deleted_at IS NULL")
        .param("id", membershipId)
        .query(Long.class)
        .single();
  }

  private void sendInvitationMail(String email, Long officeId) {
    String officeName =
        jdbcClient
            .sql("SELECT name FROM office WHERE id = :id")
            .param("id", officeId)
            .query(String.class)
            .single();
    MailMessage message =
        new MailMessage(
            email,
            "[사이로] " + officeName + " 사무소에서 초대를 보냈습니다",
            officeName
                + " 사무소가 구성원으로 초대했습니다. 로그인한 뒤 "
                + frontendBaseUrl
                + "/memberships 에서 7일 안에 수락해 주세요.");
    AfterCommit.run(() -> mailSender.send(message));
  }

  private Row lockInOffice(StaffPrincipal owner, Long membershipId) {
    return repository
        .lock(membershipId)
        .filter(found -> found.officeId() == owner.officeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private void requireDecidable(Row row, Instant now) {
    MembershipRules.requireLivePending(row, now);
    if (row.requestSource() == OfficeRequestSource.INVITE && row.invitationAcceptedAt() == null) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }

  private void requireInvitePending(Row row) {
    if (row.requestSource() != OfficeRequestSource.INVITE
        || row.status() != OfficeMembershipStatus.PENDING) {
      throw new BusinessException(ErrorCode.INVALID_TRANSITION);
    }
  }
}
