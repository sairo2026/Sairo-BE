package com.sairo.be.domain.visit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class ConfirmationDeliveryRepository {

  public record DeliveryRow(
      Long id,
      Long coordinationId,
      Long candidatePropertyId,
      long scheduleVersion,
      String recipientType,
      boolean delivered,
      String channel,
      Instant deliveredAt,
      Instant recordedAt) {}

  private static final String COLUMNS =
      "id, coordination_id, candidate_property_id, schedule_version, recipient_type, delivered,"
          + " channel, delivered_at, recorded_at";

  private final JdbcClient jdbcClient;

  public ConfirmationDeliveryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public DeliveryRow insert(
      Long coordinationId,
      Long candidatePropertyId,
      long scheduleVersion,
      String recipientType,
      boolean delivered,
      String channel,
      Instant deliveredAt,
      Long recordedByMembershipId,
      Instant recordedAt) {
    return jdbcClient
        .sql(
            "INSERT INTO visit_confirmation_delivery (coordination_id, candidate_property_id,"
                + " schedule_version, recipient_type, delivered, channel, delivered_at,"
                + " recorded_by_membership_id, recorded_at) VALUES (:coordination, :candidate,"
                + " :version, :recipient, :delivered, :channel, :deliveredAt, :recorder,"
                + " :recordedAt) RETURNING "
                + COLUMNS)
        .param("coordination", coordinationId)
        .param("candidate", candidatePropertyId)
        .param("version", scheduleVersion)
        .param("recipient", recipientType)
        .param("delivered", delivered)
        .param("channel", channel)
        .param("deliveredAt", deliveredAt == null ? null : Timestamp.from(deliveredAt))
        .param("recorder", recordedByMembershipId)
        .param("recordedAt", Timestamp.from(recordedAt))
        .query((rs, rowNum) -> row(rs))
        .single();
  }

  public List<DeliveryRow> listByCoordination(Long coordinationId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM visit_confirmation_delivery WHERE coordination_id = :coordination ORDER"
                + " BY schedule_version DESC, id DESC")
        .param("coordination", coordinationId)
        .query((rs, rowNum) -> row(rs))
        .list();
  }

  private static DeliveryRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    long candidate = rs.getLong("candidate_property_id");
    Long candidateId = rs.wasNull() ? null : candidate;
    Timestamp deliveredAt = rs.getTimestamp("delivered_at");
    return new DeliveryRow(
        rs.getLong("id"),
        rs.getLong("coordination_id"),
        candidateId,
        rs.getLong("schedule_version"),
        rs.getString("recipient_type"),
        rs.getBoolean("delivered"),
        rs.getString("channel"),
        deliveredAt == null ? null : deliveredAt.toInstant(),
        rs.getTimestamp("recorded_at").toInstant());
  }
}
