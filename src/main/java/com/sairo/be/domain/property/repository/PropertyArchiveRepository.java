package com.sairo.be.domain.property.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class PropertyArchiveRepository {

  public static final String ARCHIVE_REVOKE_REASON = "PROPERTY_ARCHIVED";
  private static final String REQUEST_CLOSE_REASON = "매물이 보관되어 신청 허용이 회수됐습니다.";

  private static final String[] REFERENCE_TABLES = {
    "property_owner_occupant",
    "customer_property_interest",
    "visit_candidate_property",
    "contract",
    "property_access_approval",
    "visit_property_apply_grant"
  };

  private final JdbcClient jdbcClient;

  public PropertyArchiveRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public boolean hasActiveCoordination(long officeId, long propertyId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM visit_candidate_property cp JOIN visit_coordination vc"
            + " ON vc.id = cp.coordination_id WHERE cp.property_id = :propertyId AND vc.office_id"
            + " = :officeId AND vc.status NOT IN ('VISIT_COMPLETED','VISIT_INCOMPLETE','CANCELLED'))"
            + " OR EXISTS (SELECT 1 FROM visit_schedule_item s JOIN visit_candidate_property cp"
            + " ON cp.id = s.candidate_property_id WHERE cp.property_id = :propertyId AND"
            + " s.result = 'SCHEDULED')",
        officeId,
        propertyId);
  }

  public boolean hasSignedContract(long officeId, long propertyId) {
    return exists(
        "SELECT EXISTS (SELECT 1 FROM contract WHERE property_id = :propertyId AND office_id ="
            + " :officeId AND contract_status = 'SIGNED')",
        officeId,
        propertyId);
  }

  public boolean hasReferences(long propertyId) {
    for (String table : REFERENCE_TABLES) {
      Boolean found =
          jdbcClient
              .sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE property_id = :propertyId)")
              .param("propertyId", propertyId)
              .query(Boolean.class)
              .single();
      if (Boolean.TRUE.equals(found)) {
        return true;
      }
    }
    return false;
  }

  public void deleteDraftRows(long propertyId) {
    for (String table :
        List.of(
            "property_offer", "property_field_value", "property_photo", "entity_lifecycle_event")) {
      jdbcClient
          .sql("DELETE FROM " + table + " WHERE property_id = :propertyId")
          .param("propertyId", propertyId)
          .update();
    }
    jdbcClient
        .sql("DELETE FROM property WHERE id = :propertyId")
        .param("propertyId", propertyId)
        .update();
  }

  public int revokeApplyGrants(long propertyId, Instant now) {
    List<Long> revoked =
        jdbcClient
            .sql(
                "UPDATE visit_property_apply_grant SET revoked_at = :now, revoke_source ="
                    + " 'SYSTEM', revoke_reason = :reason WHERE property_id = :propertyId AND"
                    + " revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)"
                    + " RETURNING id")
            .param("now", java.sql.Timestamp.from(now))
            .param("reason", ARCHIVE_REVOKE_REASON)
            .param("propertyId", propertyId)
            .query(Long.class)
            .list();
    for (Long grantId : revoked) {
      int closed =
          jdbcClient
              .sql(
                  "UPDATE visit_request SET status = 'EXPIRED', status_reason = :reason,"
                      + " closed_at = :now, updated_at = :now, version = version + 1 WHERE"
                      + " access_grant_id = :grantId AND status = 'PENDING'")
              .param("reason", REQUEST_CLOSE_REASON)
              .param("now", java.sql.Timestamp.from(now))
              .param("grantId", grantId)
              .update();
      jdbcClient
          .sql(
              "INSERT INTO visit_property_apply_grant_event (grant_id, event_type, source, reason,"
                  + " affected_request_count, occurred_at) VALUES (:grantId, 'REVOKED', 'SYSTEM',"
                  + " :reason, :closed, :now)")
          .param("grantId", grantId)
          .param("reason", ARCHIVE_REVOKE_REASON)
          .param("closed", closed)
          .param("now", java.sql.Timestamp.from(now))
          .update();
    }
    return revoked.size();
  }

  private boolean exists(String sql, long officeId, long propertyId) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(sql)
            .param("propertyId", propertyId)
            .param("officeId", officeId)
            .query(Boolean.class)
            .single());
  }
}
