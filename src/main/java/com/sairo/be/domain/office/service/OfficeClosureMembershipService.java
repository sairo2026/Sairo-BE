package com.sairo.be.domain.office.service;

import com.sairo.be.global.support.AdvisoryLock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사무소 즉시 종료(SETTING-03)의 소속 정리다. 대표를 포함한 모든 승인·접근정지 소속을 해제하고 대기 중 참여 요청·초대를 취소하며 진행 중 대표 이전을 철회로
 * 닫는다. 구성원 개인 계정은 건드리지 않는다. 대표 이전·나가기·참여 승인과 같은 잠금 키로 직렬화한다.
 */
@Profile("!migrate")
@Service
public class OfficeClosureMembershipService {

  static final String REASON = "사무소 종료";

  private final JdbcClient jdbcClient;
  private final AdvisoryLock advisoryLock;
  private final MemberSessionRefreshService sessionRefreshService;

  public OfficeClosureMembershipService(
      JdbcClient jdbcClient,
      AdvisoryLock advisoryLock,
      MemberSessionRefreshService sessionRefreshService) {
    this.jdbcClient = jdbcClient;
    this.advisoryLock = advisoryLock;
    this.sessionRefreshService = sessionRefreshService;
  }

  /** 대표 이전·나가기·참여 승인과 같은 키를 잡은 뒤 요청자가 지금도 승인된 대표인지 DB에서 다시 확인한다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean lockAndCheckOwner(Long officeId, Long membershipId) {
    advisoryLock.lockAllForTransaction(
        List.of(
            "office-owner:" + officeId,
            "owner-transfer:" + officeId,
            "membership-quota:" + officeId));
    return jdbcClient
        .sql(
            "SELECT id FROM office_membership WHERE id = :id AND office_id = :office AND role ="
                + " 'OWNER' AND status = 'APPROVED' FOR UPDATE")
        .param("id", membershipId)
        .param("office", officeId)
        .query(Long.class)
        .optional()
        .isPresent();
  }

  /** 소속을 정리하고 세션을 끝낼 사용자 ID를 돌려준다. 세션 종료는 커밋 뒤에 한다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<Long> releaseAll(Long officeId, Instant now) {
    Timestamp at = Timestamp.from(now);
    jdbcClient
        .sql("UPDATE office SET closed_at = COALESCE(closed_at, :at) WHERE id = :office")
        .param("at", at)
        .param("office", officeId)
        .update();
    jdbcClient
        .sql(
            "UPDATE office_owner_transfer SET status = 'WITHDRAWN', withdrawn_at = :at WHERE"
                + " office_id = :office AND status IN ('PENDING','UNDER_REVIEW')")
        .param("at", at)
        .param("office", officeId)
        .update();
    for (Row row : rows(officeId, "'PENDING'")) {
      jdbcClient
          .sql(
              "UPDATE office_membership SET status = 'CANCELLED', cancelled_at = :at, resolved_at"
                  + " = :at, updated_at = :at, version = version + 1 WHERE id = :id")
          .param("at", at)
          .param("id", row.id())
          .update();
      history(row.id(), row.status(), "CANCELLED", at);
    }
    List<Long> users = new ArrayList<>();
    for (Row row : rows(officeId, "'APPROVED','SUSPENDED'")) {
      jdbcClient
          .sql(
              "UPDATE office_membership SET status = 'REVOKED', revoked_at = :at, revoked_by_type"
                  + " = 'SYSTEM', revocation_reason = :reason, updated_at = :at, version ="
                  + " version + 1 WHERE id = :id")
          .param("at", at)
          .param("reason", REASON)
          .param("id", row.id())
          .update();
      history(row.id(), row.status(), "REVOKED", at);
      users.add(row.userId());
    }
    sessionRefreshService.terminateAfterCommit(users);
    return users;
  }

  private record Row(long id, long userId, String status) {}

  private List<Row> rows(Long officeId, String statuses) {
    return jdbcClient
        .sql(
            "SELECT id, user_id, status FROM office_membership WHERE office_id = :office AND"
                + " status IN ("
                + statuses
                + ") ORDER BY id FOR UPDATE")
        .param("office", officeId)
        .query((rs, i) -> new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getString("status")))
        .list();
  }

  private void history(long membershipId, String from, String to, Timestamp at) {
    jdbcClient
        .sql(
            "INSERT INTO office_membership_status_history (membership_id, from_status, to_status,"
                + " changed_by_type, reason, created_at) VALUES (:id, :from, :to, 'SYSTEM',"
                + " :reason, :at)")
        .param("id", membershipId)
        .param("from", from)
        .param("to", to)
        .param("reason", REASON)
        .param("at", at)
        .update();
  }
}
