package com.sairo.be.domain.migration.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Profile("!migrate")
@Repository
public class ExportJobJdbcRepository {

  public record Row(
      long id,
      long officeId,
      long createdByMembershipId,
      String scope,
      List<String> tableCodes,
      String status,
      String fileObjectKey,
      Instant fileExpiresAt,
      String failureReason,
      Instant createdAt) {}

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String COLUMNS =
      "id, office_id, created_by_membership_id, scope, table_codes::text, status,"
          + " file_object_key, file_expires_at, failure_reason, created_at";

  private static final RowMapper<Row> ROW_MAPPER = ExportJobJdbcRepository::mapRow;

  private final JdbcClient jdbcClient;

  public ExportJobJdbcRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public long insert(
      long officeId,
      long membershipId,
      String scope,
      List<String> tableCodes,
      Instant createdAt,
      Instant fileExpiresAt) {
    String json =
        tableCodes == null || tableCodes.isEmpty()
            ? null
            : MAPPER.valueToTree(tableCodes).toString();
    Long id =
        jdbcClient
            .sql(
                "INSERT INTO export_job (office_id, created_by_membership_id, scope, table_codes,"
                    + " status, file_expires_at, created_at, updated_at) VALUES (:officeId,"
                    + " :membershipId, :scope, CAST(:tables AS jsonb), 'PENDING', :expires,"
                    + " :createdAt, :createdAt) RETURNING id")
            .param("officeId", officeId)
            .param("membershipId", membershipId)
            .param("scope", scope)
            .param("tables", json)
            .param("expires", Timestamp.from(fileExpiresAt))
            .param("createdAt", Timestamp.from(createdAt))
            .query(Long.class)
            .single();
    return id;
  }

  public Optional<Row> findById(long id) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM export_job WHERE id = :id")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public Optional<Row> findByIdAndOffice(long id, long officeId) {
    return jdbcClient
        .sql("SELECT " + COLUMNS + " FROM export_job WHERE id = :id AND office_id = :officeId")
        .param("id", id)
        .param("officeId", officeId)
        .query(ROW_MAPPER)
        .optional();
  }

  public Optional<Row> lockPending(long id) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM export_job WHERE id = :id AND status = 'PENDING' FOR UPDATE SKIP LOCKED")
        .param("id", id)
        .query(ROW_MAPPER)
        .optional();
  }

  public List<Long> findPendingIds(int limit) {
    return jdbcClient
        .sql("SELECT id FROM export_job WHERE status = 'PENDING' ORDER BY id LIMIT :limit")
        .param("limit", limit)
        .query(Long.class)
        .list();
  }

  public List<Row> findExpiredWithFile(Instant now, int limit) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM export_job WHERE file_object_key IS NOT NULL AND file_expires_at <= :now"
                + " ORDER BY id LIMIT :limit")
        .param("now", Timestamp.from(now))
        .param("limit", limit)
        .query(ROW_MAPPER)
        .list();
  }

  public void complete(long id, String fileObjectKey, Instant now) {
    jdbcClient
        .sql(
            "UPDATE export_job SET status = 'COMPLETED', file_object_key = :key, updated_at ="
                + " :now WHERE id = :id AND status = 'PENDING'")
        .param("key", fileObjectKey)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void failPending(long id, String reason, Instant now) {
    jdbcClient
        .sql(
            "UPDATE export_job SET status = 'FAILED', failure_reason = :reason, updated_at = :now"
                + " WHERE id = :id AND status = 'PENDING'")
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public void clearFile(long id, Instant now) {
    jdbcClient
        .sql("UPDATE export_job SET file_object_key = NULL, updated_at = :now WHERE id = :id")
        .param("now", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  public List<Row> list(
      long officeId,
      Long creatorMembershipId,
      Collection<String> statuses,
      Instant now,
      int offset,
      int limit) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM export_job WHERE office_id = :officeId AND file_expires_at > :now"
                + " AND status IN (:statuses) AND (CAST(:creator AS BIGINT) IS NULL OR"
                + " created_by_membership_id = :creator) ORDER BY created_at DESC, id DESC"
                + " OFFSET :offset LIMIT :limit")
        .param("officeId", officeId)
        .param("now", Timestamp.from(now))
        .param("statuses", statuses)
        .param("creator", creatorMembershipId)
        .param("offset", offset)
        .param("limit", limit)
        .query(ROW_MAPPER)
        .list();
  }

  public long count(
      long officeId, Long creatorMembershipId, Collection<String> statuses, Instant now) {
    Long total =
        jdbcClient
            .sql(
                "SELECT count(*) FROM export_job WHERE office_id = :officeId AND file_expires_at >"
                    + " :now AND status IN (:statuses) AND (CAST(:creator AS BIGINT) IS NULL OR"
                    + " created_by_membership_id = :creator)")
            .param("officeId", officeId)
            .param("now", Timestamp.from(now))
            .param("statuses", statuses)
            .param("creator", creatorMembershipId)
            .query(Long.class)
            .single();
    return total;
  }

  public Set<String> findAllowedTables(long officeId) {
    return new LinkedHashSet<>(
        jdbcClient
            .sql("SELECT table_code FROM office_export_table_policy WHERE office_id = :officeId")
            .param("officeId", officeId)
            .query(String.class)
            .list());
  }

  public void replaceAllowedTables(long officeId, Collection<String> codes) {
    jdbcClient
        .sql("DELETE FROM office_export_table_policy WHERE office_id = :officeId")
        .param("officeId", officeId)
        .update();
    for (String code : codes) {
      jdbcClient
          .sql(
              "INSERT INTO office_export_table_policy (office_id, table_code) VALUES (:officeId,"
                  + " :code)")
          .param("officeId", officeId)
          .param("code", code)
          .update();
    }
  }

  private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
    String tables = rs.getString(5);
    List<String> codes = new ArrayList<>();
    if (tables != null) {
      JsonNode array = MAPPER.readTree(tables);
      array.forEach(node -> codes.add(node.asString()));
    }
    return new Row(
        rs.getLong(1),
        rs.getLong(2),
        rs.getLong(3),
        rs.getString(4),
        List.copyOf(codes),
        rs.getString(6),
        rs.getString(7),
        rs.getTimestamp(8).toInstant(),
        rs.getString(9),
        rs.getTimestamp(10).toInstant());
  }
}
