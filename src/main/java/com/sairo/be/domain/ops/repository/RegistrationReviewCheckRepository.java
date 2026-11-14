package com.sairo.be.domain.ops.repository;

import com.sairo.be.domain.ops.entity.ReviewItemCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class RegistrationReviewCheckRepository {

  public record Row(
      ReviewItemCode itemCode, boolean checked, String checkedByOperatorName, Instant checkedAt) {}

  private final JdbcClient jdbcClient;

  public RegistrationReviewCheckRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Map<ReviewItemCode, Row> find(long registrationId, int revisionNo) {
    Map<ReviewItemCode, Row> rows = new LinkedHashMap<>();
    jdbcClient
        .sql(
            "SELECT c.item_code, c.checked, c.checked_at, o.name FROM"
                + " office_registration_review_check c LEFT JOIN operator_account o"
                + " ON o.id = c.checked_by_operator_id WHERE c.registration_id = :id"
                + " AND c.revision_no = :revision")
        .param("id", registrationId)
        .param("revision", revisionNo)
        .query(
            rs -> {
              ReviewItemCode code = ReviewItemCode.valueOf(rs.getString("item_code"));
              Timestamp checkedAt = rs.getTimestamp("checked_at");
              rows.put(
                  code,
                  new Row(
                      code,
                      rs.getBoolean("checked"),
                      rs.getString("name"),
                      checkedAt == null ? null : checkedAt.toInstant()));
            });
    return rows;
  }

  public void upsert(
      long registrationId,
      int revisionNo,
      ReviewItemCode code,
      boolean checked,
      long operatorId,
      Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO office_registration_review_check (registration_id, revision_no,"
                + " item_code, checked, checked_by_operator_id, checked_at, created_at,"
                + " updated_at) VALUES (:id, :revision, :code, :checked, :operator, :checkedAt,"
                + " :now, :now) ON CONFLICT (registration_id, revision_no, item_code) DO UPDATE"
                + " SET checked = EXCLUDED.checked,"
                + " checked_by_operator_id = EXCLUDED.checked_by_operator_id,"
                + " checked_at = EXCLUDED.checked_at, updated_at = EXCLUDED.updated_at")
        .param("id", registrationId)
        .param("revision", revisionNo)
        .param("code", code.name())
        .param("checked", checked)
        .param("operator", checked ? operatorId : null)
        .param("checkedAt", checked ? Timestamp.from(now) : null)
        .param("now", Timestamp.from(now))
        .update();
  }
}
