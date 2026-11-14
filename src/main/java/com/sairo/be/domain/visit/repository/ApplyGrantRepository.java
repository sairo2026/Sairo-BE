package com.sairo.be.domain.visit.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 신청 허용 표(visit_property_apply_grant)와 이력 표를 다룬다. 상태는 저장 컬럼이 아니라 시각으로 계산한다. */
@Profile("!migrate")
@Repository
public class ApplyGrantRepository {

  public record GrantRow(
      Long id,
      Long approvalId,
      Long propertyId,
      Long ownerOfficeId,
      Long granteeOfficeId,
      Long granteeMembershipId,
      Long grantedByMembershipId,
      Instant grantedAt,
      Instant expiresAt,
      Instant revokedAt,
      String revokeSource,
      String revokeReason,
      String state,
      int openRequestCount) {}

  public record ReceivedRow(
      Long grantId,
      Long approvalId,
      Long propertyId,
      Long ownerOfficeId,
      String ownerOfficeName,
      Long granteeMembershipId,
      String propertyName,
      String propertyAddress,
      String categoryName,
      Instant approvalValidUntil,
      Instant grantExpiresAt,
      Instant conditionAppliesUntil) {}

  public record PendingRequest(Long requestId, Long coordinationId) {}

  /** 허용이 활성인지 판정하는 조건이다. g는 허용, a는 출입승인 별칭이어야 한다. */
  static final String ACTIVE =
      " g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at > :now)"
          + " AND a.revoked_at IS NULL AND a.valid_until > :now";

  private static final String STATE =
      "CASE WHEN g.revoked_at IS NOT NULL THEN 'REVOKED'"
          + " WHEN (g.expires_at IS NOT NULL AND g.expires_at <= :now) OR a.revoked_at IS NOT NULL"
          + " OR a.valid_until <= :now THEN 'EXPIRED' ELSE 'ACTIVE' END";

  private static final String SELECT =
      "SELECT g.id, g.approval_id, g.property_id, g.owner_office_id, g.grantee_office_id,"
          + " g.grantee_membership_id, g.granted_by_membership_id, g.granted_at, g.expires_at,"
          + " g.revoked_at, g.revoke_source, g.revoke_reason, "
          + STATE
          + " AS state, (SELECT count(*) FROM visit_request vr WHERE vr.access_grant_id = g.id"
          + " AND vr.status IN ('PENDING','SELECTED')) AS open_count"
          + " FROM visit_property_apply_grant g JOIN property_access_approval a ON a.id ="
          + " g.approval_id";

  private final JdbcClient jdbcClient;

  public ApplyGrantRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Optional<Long> findActiveGrantId(
      Long granteeOfficeId,
      Long granteeMembershipId,
      Long approvalId,
      Long propertyId,
      Instant now) {
    return jdbcClient
        .sql(
            "SELECT g.id FROM visit_property_apply_grant g JOIN property_access_approval a ON a.id"
                + " = g.approval_id WHERE g.approval_id = :approval AND g.property_id = :property"
                + " AND g.grantee_office_id = :office AND (g.grantee_membership_id IS NULL OR"
                + " g.grantee_membership_id = :member) AND"
                + ACTIVE
                + " ORDER BY g.grantee_membership_id NULLS LAST, g.id LIMIT 1")
        .param("approval", approvalId)
        .param("property", propertyId)
        .param("office", granteeOfficeId)
        .param("member", granteeMembershipId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .optional();
  }

  public boolean hasActiveForProperty(
      Long granteeOfficeId,
      Long granteeMembershipId,
      Long propertyId,
      Long ownerOfficeId,
      Instant now) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM visit_property_apply_grant g JOIN"
                + " property_access_approval a ON a.id = g.approval_id WHERE g.property_id ="
                + " :property AND g.owner_office_id = :owner AND g.grantee_office_id = :office AND"
                + " (g.grantee_membership_id IS NULL OR g.grantee_membership_id = :member) AND"
                + " a.approval_role = 'PRIMARY' AND"
                + ACTIVE
                + ")")
        .param("property", propertyId)
        .param("owner", ownerOfficeId)
        .param("office", granteeOfficeId)
        .param("member", granteeMembershipId)
        .param("now", Timestamp.from(now))
        .query(Boolean.class)
        .single();
  }

  /** 후보 매물 표시에 쓰는 허용이다. 활성 허용이 있으면 그것, 없으면 가장 최근 허용이다. */
  public Optional<GrantRow> latestForCandidate(
      Long granteeOfficeId, Long granteeMembershipId, Long propertyId, Instant now) {
    return jdbcClient
        .sql(
            SELECT
                + " WHERE g.property_id = :property AND g.grantee_office_id = :office AND"
                + " (g.grantee_membership_id IS NULL OR g.grantee_membership_id = :member)"
                + " ORDER BY CASE WHEN "
                + STATE
                + " = 'ACTIVE' THEN 0 ELSE 1 END, g.granted_at DESC, g.id DESC LIMIT 1")
        .param("property", propertyId)
        .param("office", granteeOfficeId)
        .param("member", granteeMembershipId)
        .param("now", Timestamp.from(now))
        .query((rs, rowNum) -> grant(rs))
        .optional();
  }

  public Optional<GrantRow> find(Long grantId, Long approvalId, Instant now) {
    return jdbcClient
        .sql(SELECT + " WHERE g.id = :id AND g.approval_id = :approval")
        .param("id", grantId)
        .param("approval", approvalId)
        .param("now", Timestamp.from(now))
        .query((rs, rowNum) -> grant(rs))
        .optional();
  }

  public boolean lock(Long grantId, Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT id FROM visit_property_apply_grant WHERE id = :id AND approval_id = :approval"
                + " FOR UPDATE")
        .param("id", grantId)
        .param("approval", approvalId)
        .query(Long.class)
        .optional()
        .isPresent();
  }

  /** 같은 허용 대상의 회수되지 않은 허용이다. 활성 판정은 호출하는 쪽이 state로 한다. */
  public List<GrantRow> unrevokedFor(Long approvalId, Long granteeOfficeId, Instant now) {
    return jdbcClient
        .sql(
            SELECT
                + " WHERE g.approval_id = :approval AND g.grantee_office_id = :office AND"
                + " g.revoked_at IS NULL ORDER BY g.id")
        .param("approval", approvalId)
        .param("office", granteeOfficeId)
        .param("now", Timestamp.from(now))
        .query((rs, rowNum) -> grant(rs))
        .list();
  }

  public long countByApproval(Long approvalId, Set<String> states, Instant now) {
    return jdbcClient
        .sql(
            "SELECT count(*) FROM visit_property_apply_grant g JOIN property_access_approval a ON"
                + " a.id = g.approval_id WHERE g.approval_id = :approval AND "
                + STATE
                + " IN (:states)")
        .param("approval", approvalId)
        .param("states", states)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public List<GrantRow> pageByApproval(
      Long approvalId, Set<String> states, Instant now, int limit, long offset) {
    return jdbcClient
        .sql(
            SELECT
                + " WHERE g.approval_id = :approval AND "
                + STATE
                + " IN (:states) ORDER BY g.granted_at DESC, g.id DESC LIMIT :limit OFFSET"
                + " :offset")
        .param("approval", approvalId)
        .param("states", states)
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .param("offset", offset)
        .query((rs, rowNum) -> grant(rs))
        .list();
  }

  public Long insert(
      Long approvalId,
      Long propertyId,
      Long ownerOfficeId,
      Long granteeOfficeId,
      Long granteeMembershipId,
      Long grantedByMembershipId,
      Instant now,
      Instant expiresAt) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_property_apply_grant (approval_id, property_id, owner_office_id,"
                + " grantee_office_id, grantee_membership_id, granted_by_membership_id,"
                + " granted_at, expires_at) VALUES (:approval, :property, :owner, :office,"
                + " :member, :grantedBy, :now, :expires) RETURNING id")
        .param("approval", approvalId)
        .param("property", propertyId)
        .param("owner", ownerOfficeId)
        .param("office", granteeOfficeId)
        .param("member", granteeMembershipId)
        .param("grantedBy", grantedByMembershipId)
        .param("now", Timestamp.from(now))
        .param("expires", expiresAt == null ? null : Timestamp.from(expiresAt))
        .query(Long.class)
        .single();
  }

  public void revokeByOffice(Long grantId, Long membershipId, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_property_apply_grant SET revoked_at = :now, revoke_source = 'OFFICE',"
                + " revoked_by_membership_id = :member, revoke_reason = :reason WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("member", membershipId)
        .param("reason", reason)
        .param("id", grantId)
        .update();
  }

  public void revokeBySystem(Long grantId, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE visit_property_apply_grant SET revoked_at = :now, revoke_source = 'SYSTEM',"
                + " revoke_reason = :reason WHERE id = :id AND revoked_at IS NULL")
        .param("now", Timestamp.from(now))
        .param("reason", reason)
        .param("id", grantId)
        .update();
  }

  /** 허용 이력을 남긴다. 만료 이력은 허용당 한 번이므로 이미 있으면 남기지 않는다. */
  public void recordEvent(
      Long grantId,
      String eventType,
      Long actorMembershipId,
      String reason,
      int affectedRequestCount,
      Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO visit_property_apply_grant_event (grant_id, event_type, source,"
                + " actor_membership_id, reason, affected_request_count, occurred_at) VALUES"
                + " (:grant, :type, :source, :actor, :reason, :count, :now) ON CONFLICT DO"
                + " NOTHING")
        .param("grant", grantId)
        .param("type", eventType)
        .param("source", actorMembershipId == null ? "SYSTEM" : "OFFICE")
        .param("actor", actorMembershipId)
        .param("reason", reason)
        .param("count", affectedRequestCount)
        .param("now", Timestamp.from(now))
        .update();
  }

  public List<Long> unrevokedIdsByApproval(Long approvalId) {
    return jdbcClient
        .sql(
            "SELECT id FROM visit_property_apply_grant WHERE approval_id = :approval AND"
                + " revoked_at IS NULL ORDER BY id FOR UPDATE")
        .param("approval", approvalId)
        .query(Long.class)
        .list();
  }

  public List<Long> activeIdsByProperty(Long propertyId, Instant now) {
    return jdbcClient
        .sql(
            "SELECT id FROM visit_property_apply_grant WHERE property_id = :property AND"
                + " revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now) ORDER BY id"
                + " FOR UPDATE")
        .param("property", propertyId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .list();
  }

  public List<PendingRequest> pendingRequests(Long grantId) {
    return jdbcClient
        .sql(
            "SELECT id, coordination_id FROM visit_request WHERE access_grant_id = :grant AND"
                + " status = 'PENDING' ORDER BY coordination_id, id")
        .param("grant", grantId)
        .query((rs, rowNum) -> new PendingRequest(rs.getLong("id"), rs.getLong("coordination_id")))
        .list();
  }

  public List<Long> keptScheduleItemIds(Long grantId) {
    return jdbcClient
        .sql(
            "SELECT i.id FROM visit_schedule_item i JOIN visit_request vr ON vr.id ="
                + " i.confirmed_request_id WHERE vr.access_grant_id = :grant AND i.result ="
                + " 'SCHEDULED' ORDER BY i.scheduled_start_at, i.id")
        .param("grant", grantId)
        .query(Long.class)
        .list();
  }

  public boolean isVerifiedOffice(Long officeId) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM office o WHERE o.id = :office AND EXISTS (SELECT 1 FROM"
                + " office_registration r WHERE r.resulting_office_id = o.id AND r.status ="
                + " 'APPROVED') AND o.closed_at IS NULL)")
        .param("office", officeId)
        .query(Boolean.class)
        .single();
  }

  public boolean isApprovedBroker(Long membershipId, Long officeId) {
    return jdbcClient
        .sql(
            "SELECT EXISTS (SELECT 1 FROM office_membership WHERE id = :member AND office_id ="
                + " :office AND status = 'APPROVED' AND role IN ('OWNER','AGENT'))")
        .param("member", membershipId)
        .param("office", officeId)
        .query(Boolean.class)
        .single();
  }

  public long countReceived(Long officeId, Long membershipId, String keyword, Instant now) {
    return jdbcClient
        .sql("SELECT count(*)" + receivedFrom(keyword))
        .param("office", officeId)
        .param("member", membershipId)
        .param("keyword", like(keyword))
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public List<ReceivedRow> pageReceived(
      Long officeId, Long membershipId, String keyword, Instant now, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT g.id, g.approval_id, g.property_id, g.owner_office_id, o.name AS office_name,"
                + " g.grantee_membership_id, p.name AS property_name, p.address,"
                + " c.name AS category_name, a.valid_until, g.expires_at, (SELECT"
                + " vc.applies_until FROM visit_approver_condition vc WHERE vc.approval_id ="
                + " a.id AND vc.status = 'ACTIVE') AS condition_until"
                + receivedFrom(keyword)
                + " ORDER BY g.granted_at DESC, g.id DESC LIMIT :limit OFFSET :offset")
        .param("office", officeId)
        .param("member", membershipId)
        .param("keyword", like(keyword))
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) ->
                new ReceivedRow(
                    rs.getLong("id"),
                    rs.getLong("approval_id"),
                    rs.getLong("property_id"),
                    rs.getLong("owner_office_id"),
                    rs.getString("office_name"),
                    rs.getObject("grantee_membership_id", Long.class),
                    rs.getString("property_name"),
                    rs.getString("address"),
                    rs.getString("category_name"),
                    instant(rs, "valid_until"),
                    instant(rs, "expires_at"),
                    instant(rs, "condition_until")))
        .list();
  }

  public List<String> activeDealTypes(Long propertyId) {
    return jdbcClient
        .sql(
            "SELECT deal_type FROM property_offer WHERE property_id = :property AND status IN"
                + " ('AVAILABLE','NEGOTIATING') ORDER BY CASE deal_type WHEN 'SALE' THEN 0 WHEN"
                + " 'JEONSE' THEN 1 ELSE 2 END")
        .param("property", propertyId)
        .query(String.class)
        .list();
  }

  private static String receivedFrom(String keyword) {
    List<String> where = new ArrayList<>();
    where.add("g.grantee_office_id = :office");
    where.add("(g.grantee_membership_id IS NULL OR g.grantee_membership_id = :member)");
    where.add("a.approval_role = 'PRIMARY'");
    where.add("p.deleted_at IS NULL");
    if (keyword != null) {
      where.add("p.name ILIKE :keyword ESCAPE '\\'");
    }
    return " FROM visit_property_apply_grant g JOIN property_access_approval a ON a.id ="
        + " g.approval_id JOIN property p ON p.id = g.property_id JOIN office o ON o.id ="
        + " g.owner_office_id JOIN property_category c ON c.id = p.category_id WHERE "
        + String.join(" AND ", where)
        + " AND"
        + ACTIVE;
  }

  private static String like(String keyword) {
    if (keyword == null) {
      return null;
    }
    return "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  private static GrantRow grant(ResultSet rs) throws SQLException {
    return new GrantRow(
        rs.getLong("id"),
        rs.getLong("approval_id"),
        rs.getLong("property_id"),
        rs.getLong("owner_office_id"),
        rs.getLong("grantee_office_id"),
        rs.getObject("grantee_membership_id", Long.class),
        rs.getLong("granted_by_membership_id"),
        instant(rs, "granted_at"),
        instant(rs, "expires_at"),
        instant(rs, "revoked_at"),
        rs.getString("revoke_source"),
        rs.getString("revoke_reason"),
        rs.getString("state"),
        rs.getInt("open_count"));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
