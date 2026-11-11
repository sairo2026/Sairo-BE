package com.sairo.be.domain.visit.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 승인자 프로필, 프로필과 출입승인의 연결, 인증 사건, 조건 일괄 적용 기록을 다룬다. */
@Profile("!migrate")
@Repository
public class ApproverProfileRepository {

  public record ProfileRow(
      Long id,
      String phoneHash,
      String phoneLast4,
      String status,
      Instant createdAt,
      Instant lastLoginAt) {

    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }

  public record LinkRow(Long id, Long profileId, Long approvalId, String status) {}

  public record PortalRow(
      Long approvalId,
      String linkState,
      Long propertyId,
      String propertyName,
      String propertyAddress,
      String approverType,
      String approvalRole,
      Long ownerOfficeId,
      String ownerOfficeName,
      String officeContactPhone,
      Instant validFrom,
      Instant validUntil,
      Instant revokedAt) {}

  private static final String PROFILE =
      "SELECT id, phone_hash, phone_last4, status, created_at, last_login_at FROM"
          + " approver_profile";

  private final JdbcClient jdbcClient;

  public ApproverProfileRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<ProfileRow> findActiveByHash(String phoneHash) {
    return jdbcClient
        .sql(PROFILE + " WHERE phone_hash = :hash AND status = 'ACTIVE'")
        .param("hash", phoneHash)
        .query((rs, rowNum) -> profile(rs))
        .optional();
  }

  public Optional<ProfileRow> find(Long profileId) {
    return jdbcClient
        .sql(PROFILE + " WHERE id = :id")
        .param("id", profileId)
        .query((rs, rowNum) -> profile(rs))
        .optional();
  }

  public Optional<ProfileRow> lock(Long profileId) {
    return jdbcClient
        .sql(PROFILE + " WHERE id = :id FOR UPDATE")
        .param("id", profileId)
        .query((rs, rowNum) -> profile(rs))
        .optional();
  }

  public Long insertProfile(String phoneHash, String last4, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO approver_profile (phone_hash, phone_last4, status, created_at,"
                + " last_login_at) VALUES (:hash, :last4, 'ACTIVE', :now, :now) RETURNING id")
        .param("hash", phoneHash)
        .param("last4", last4)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void touchLogin(Long profileId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE approver_profile SET last_login_at = :now, version = version + 1 WHERE id ="
                + " :id")
        .param("now", Timestamp.from(now))
        .param("id", profileId)
        .update();
  }

  public void close(Long profileId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE approver_profile SET status = 'CLOSED', phone_hash = NULL, phone_last4 ="
                + " NULL, closed_at = :now, close_source = 'SELF', version = version + 1 WHERE"
                + " id = :id")
        .param("now", Timestamp.from(now))
        .param("id", profileId)
        .update();
  }

  public Optional<LinkRow> link(Long profileId, Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT id, profile_id, approval_id, status FROM approver_profile_approval WHERE"
                + " profile_id = :profile AND approval_id = :approval")
        .param("profile", profileId)
        .param("approval", approvalId)
        .query((rs, rowNum) -> link(rs))
        .optional();
  }

  public Optional<LinkRow> lockLink(Long profileId, Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT id, profile_id, approval_id, status FROM approver_profile_approval WHERE"
                + " profile_id = :profile AND approval_id = :approval FOR UPDATE")
        .param("profile", profileId)
        .param("approval", approvalId)
        .query((rs, rowNum) -> link(rs))
        .optional();
  }

  public boolean isLinked(Long profileId, Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM approver_profile_approval pa JOIN approver_profile p ON"
                + " p.id = pa.profile_id WHERE pa.profile_id = :profile AND pa.approval_id ="
                + " :approval AND pa.status = 'LINKED' AND p.status = 'ACTIVE')")
        .param("profile", profileId)
        .param("approval", approvalId)
        .query(Boolean.class)
        .single();
  }

  public Optional<Long> linkedProfileOf(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT profile_id FROM approver_profile_approval WHERE approval_id = :approval AND"
                + " status = 'LINKED'")
        .param("approval", approvalId)
        .query(Long.class)
        .optional();
  }

  public void insertLinked(Long profileId, Long approvalId, Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO approver_profile_approval (profile_id, approval_id, status, linked_via,"
                + " proposed_at, decided_at) VALUES (:profile, :approval, 'LINKED', 'LINK_TOKEN',"
                + " :now, :now)")
        .param("profile", profileId)
        .param("approval", approvalId)
        .param("now", Timestamp.from(now))
        .update();
  }

  public void insertProposal(Long profileId, Long approvalId, Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO approver_profile_approval (profile_id, approval_id, status, linked_via,"
                + " proposed_at) VALUES (:profile, :approval, 'PENDING_CONFIRM', 'PHONE_MATCH',"
                + " :now) ON CONFLICT (profile_id, approval_id) DO NOTHING")
        .param("profile", profileId)
        .param("approval", approvalId)
        .param("now", Timestamp.from(now))
        .update();
  }

  public void setStatus(Long linkId, String status, Instant now) {
    jdbcClient
        .sql(
            "UPDATE approver_profile_approval SET status = :status, decided_at = :now,"
                + " unlinked_at = NULL WHERE id = :id")
        .param("status", status)
        .param("now", Timestamp.from(now))
        .param("id", linkId)
        .update();
  }

  public void unlink(Long linkId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE approver_profile_approval SET status = 'UNLINKED', decided_at ="
                + " COALESCE(decided_at, :now), unlinked_at = :now WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", linkId)
        .update();
  }

  /** 프로필의 연결과 제안을 모두 해제하고 해제한 연결(LINKED였던 것) 수를 돌려준다. */
  public int unlinkAll(Long profileId, Instant now) {
    Integer linked =
        jdbcClient
            .sql(
                "SELECT count(*) FROM approver_profile_approval WHERE profile_id = :profile AND"
                    + " status = 'LINKED'")
            .param("profile", profileId)
            .query(Integer.class)
            .single();
    jdbcClient
        .sql(
            "UPDATE approver_profile_approval SET status = 'UNLINKED', decided_at ="
                + " COALESCE(decided_at, :now), unlinked_at = :now WHERE profile_id = :profile AND"
                + " status IN ('LINKED','PENDING_CONFIRM')")
        .param("now", Timestamp.from(now))
        .param("profile", profileId)
        .update();
    return linked == null ? 0 : linked;
  }

  /** 같은 번호 해시로 등록된 출입승인 가운데 이 프로필에 아직 연결 행이 없고 다른 프로필에 연결되지 않은 유효한 것이다. */
  public List<Long> proposalCandidates(Long profileId, String phoneHash, Instant now) {
    return jdbcClient
        .sql(
            "SELECT a.id FROM property_access_approval a WHERE a.approver_phone_hash = :hash AND"
                + " a.revoked_at IS NULL AND a.valid_until > :now AND NOT EXISTS (SELECT 1 FROM"
                + " approver_profile_approval pa WHERE pa.approval_id = a.id AND (pa.profile_id ="
                + " :profile OR pa.status = 'LINKED')) ORDER BY a.id")
        .param("hash", phoneHash)
        .param("now", Timestamp.from(now))
        .param("profile", profileId)
        .query(Long.class)
        .list();
  }

  public boolean hasApprovalWithHash(String phoneHash, Instant now) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM property_access_approval WHERE approver_phone_hash ="
                + " :hash AND revoked_at IS NULL AND valid_until > :now)")
        .param("hash", phoneHash)
        .param("now", Timestamp.from(now))
        .query(Boolean.class)
        .single();
  }

  public int countLinks(Long profileId, String status) {
    Integer count =
        jdbcClient
            .sql(
                "SELECT count(*) FROM approver_profile_approval WHERE profile_id = :profile AND"
                    + " status = :status")
            .param("profile", profileId)
            .param("status", status)
            .query(Integer.class)
            .single();
    return count == null ? 0 : count;
  }

  /** 모아보기 목록이다. 철회·만료된 출입승인은 종료 후 30일까지만 둔다. */
  public List<PortalRow> portalRows(Long profileId, List<String> states, Instant now) {
    return jdbcClient
        .sql(
            "SELECT pa.approval_id, pa.status, a.property_id, p.name AS property_name,"
                + " p.address, a.approver_type, a.approval_role, p.office_id, o.name AS"
                + " office_name, o.representative_phone, a.valid_from, a.valid_until,"
                + " a.revoked_at FROM approver_profile_approval pa JOIN property_access_approval a"
                + " ON a.id = pa.approval_id JOIN property p ON p.id = a.property_id JOIN office o"
                + " ON o.id = p.office_id WHERE pa.profile_id = :profile AND pa.status IN"
                + " (:states) AND COALESCE(LEAST(a.revoked_at, a.valid_until), a.valid_until) >"
                + " :cutoff ORDER BY CASE pa.status WHEN 'PENDING_CONFIRM' THEN 0 ELSE 1 END,"
                + " a.valid_until DESC, a.id DESC")
        .param("profile", profileId)
        .param("states", states)
        .param("cutoff", Timestamp.from(now.minus(java.time.Duration.ofDays(30))))
        .query(
            (rs, rowNum) ->
                new PortalRow(
                    rs.getLong("approval_id"),
                    rs.getString("status"),
                    rs.getLong("property_id"),
                    rs.getString("property_name"),
                    rs.getString("address"),
                    rs.getString("approver_type"),
                    rs.getString("approval_role"),
                    rs.getLong("office_id"),
                    rs.getString("office_name"),
                    rs.getString("representative_phone"),
                    ApplyGrantRepository.instant(rs, "valid_from"),
                    ApplyGrantRepository.instant(rs, "valid_until"),
                    ApplyGrantRepository.instant(rs, "revoked_at")))
        .list();
  }

  public long pendingRequestCount(Long approvalId) {
    Long count =
        jdbcClient
            .sql(
                "SELECT count(*) FROM visit_request WHERE approval_id = :approval AND status ="
                    + " 'PENDING'")
            .param("approval", approvalId)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  public List<Instant> upcomingVisitStarts(Long approvalId, Instant now) {
    return jdbcClient
        .sql(
            "SELECT i.scheduled_start_at FROM visit_schedule_item i JOIN visit_request vr ON"
                + " vr.id = i.confirmed_request_id WHERE vr.approval_id = :approval AND i.result ="
                + " 'SCHEDULED' AND i.scheduled_start_at > :now ORDER BY i.scheduled_start_at")
        .param("approval", approvalId)
        .param("now", Timestamp.from(now))
        .query((rs, rowNum) -> ApplyGrantRepository.instant(rs, "scheduled_start_at"))
        .list();
  }

  public void recordEvent(
      Long profileId,
      Long approvalId,
      Long batchId,
      String eventType,
      String reason,
      String ipHmac,
      Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO approver_auth_event (profile_id, approval_id, batch_id, event_type,"
                + " reason, ip_hmac, hmac_key_version, occurred_at) VALUES (:profile, :approval,"
                + " :batch, :type, :reason, :ip, :keyVersion, :now)")
        .param("profile", profileId)
        .param("approval", approvalId)
        .param("batch", batchId)
        .param("type", eventType)
        .param("reason", reason)
        .param("ip", ipHmac)
        .param("keyVersion", ipHmac == null ? null : 1)
        .param("now", Timestamp.from(now))
        .update();
  }

  public Long insertBatch(Long profileId, int requestedCount, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO approver_condition_bulk_batch (profile_id, requested_count, created_at)"
                + " VALUES (:profile, :count, :now) RETURNING id")
        .param("profile", profileId)
        .param("count", requestedCount)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void insertBatchItem(
      Long batchId,
      Long approvalId,
      String result,
      Long conditionId,
      String failureCode,
      int affectedCount) {
    jdbcClient
        .sql(
            "INSERT INTO approver_condition_bulk_item (batch_id, approval_id, result,"
                + " condition_id, failure_code, affected_item_count) VALUES (:batch, :approval,"
                + " :result, :condition, :failure, :affected)")
        .param("batch", batchId)
        .param("approval", approvalId)
        .param("result", result)
        .param("condition", conditionId)
        .param("failure", failureCode)
        .param("affected", affectedCount)
        .update();
  }

  public void completeBatch(Long batchId, int applied, int needsAck, int failed, Instant now) {
    jdbcClient
        .sql(
            "UPDATE approver_condition_bulk_batch SET applied_count = :applied,"
                + " needs_impact_ack_count = :needs, failed_count = :failed, completed_at = :now"
                + " WHERE id = :id")
        .param("applied", applied)
        .param("needs", needsAck)
        .param("failed", failed)
        .param("now", Timestamp.from(now))
        .param("id", batchId)
        .update();
  }

  private static ProfileRow profile(ResultSet rs) throws SQLException {
    return new ProfileRow(
        rs.getLong("id"),
        rs.getString("phone_hash"),
        rs.getString("phone_last4"),
        rs.getString("status"),
        ApplyGrantRepository.instant(rs, "created_at"),
        ApplyGrantRepository.instant(rs, "last_login_at"));
  }

  private static LinkRow link(ResultSet rs) throws SQLException {
    return new LinkRow(
        rs.getLong("id"),
        rs.getLong("profile_id"),
        rs.getLong("approval_id"),
        rs.getString("status"));
  }
}
