package com.sairo.be.domain.office.repository;

import com.sairo.be.domain.office.entity.EvidenceType;
import com.sairo.be.domain.office.entity.RegistrationActor;
import com.sairo.be.domain.office.entity.RegistrationStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OfficeRegistrationRecordRepository {

  public record RevisionRow(
      int revisionNo, Instant submittedAt, String requestedOfficeName, String requestedAddress) {}

  public record HistoryRow(
      long id,
      RegistrationStatus fromStatus,
      RegistrationStatus toStatus,
      RegistrationActor changedByType,
      Long changedByOperatorId,
      String reason,
      Instant createdAt) {}

  private final JdbcClient jdbcClient;

  public OfficeRegistrationRecordRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public long insertRevision(
      long registrationId, int revisionNo, String officeName, String address, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO office_registration_revision (registration_id, revision_no,"
                + " requested_office_name, requested_address, submitted_at)"
                + " VALUES (:registrationId, :revisionNo, :name, :address, :now) RETURNING id")
        .param("registrationId", registrationId)
        .param("revisionNo", revisionNo)
        .param("name", officeName)
        .param("address", address)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void insertEvidence(long revisionId, EvidenceType type, String objectKey, Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO office_registration_evidence (revision_id, evidence_type, object_key,"
                + " uploaded_at) VALUES (:revisionId, :type, :key, :now)")
        .param("revisionId", revisionId)
        .param("type", type.name())
        .param("key", objectKey)
        .param("now", Timestamp.from(now))
        .update();
  }

  public Map<EvidenceType, String> evidenceKeys(long registrationId, int revisionNo) {
    Map<EvidenceType, String> keys = new LinkedHashMap<>();
    jdbcClient
        .sql(
            "SELECT e.evidence_type, e.object_key FROM office_registration_evidence e"
                + " JOIN office_registration_revision r ON r.id = e.revision_id"
                + " WHERE r.registration_id = :registrationId AND r.revision_no = :revisionNo"
                + " ORDER BY e.id")
        .param("registrationId", registrationId)
        .param("revisionNo", revisionNo)
        .query(
            rs -> {
              keys.put(
                  EvidenceType.valueOf(rs.getString("evidence_type")), rs.getString("object_key"));
            });
    return keys;
  }

  public Map<Integer, List<EvidenceType>> evidenceTypesByRevision(long registrationId) {
    Map<Integer, List<EvidenceType>> types = new LinkedHashMap<>();
    jdbcClient
        .sql(
            "SELECT r.revision_no, e.evidence_type FROM office_registration_evidence e"
                + " JOIN office_registration_revision r ON r.id = e.revision_id"
                + " WHERE r.registration_id = :registrationId ORDER BY r.revision_no, e.id")
        .param("registrationId", registrationId)
        .query(
            rs -> {
              types
                  .computeIfAbsent(rs.getInt("revision_no"), key -> new java.util.ArrayList<>())
                  .add(EvidenceType.valueOf(rs.getString("evidence_type")));
            });
    return types;
  }

  public List<RevisionRow> revisions(long registrationId) {
    return jdbcClient
        .sql(
            "SELECT revision_no, submitted_at, requested_office_name, requested_address"
                + " FROM office_registration_revision WHERE registration_id = :id"
                + " ORDER BY revision_no")
        .param("id", registrationId)
        .query(
            (rs, i) ->
                new RevisionRow(
                    rs.getInt("revision_no"),
                    rs.getTimestamp("submitted_at").toInstant(),
                    rs.getString("requested_office_name"),
                    rs.getString("requested_address")))
        .list();
  }

  public void insertHistory(
      long registrationId,
      RegistrationStatus from,
      RegistrationStatus to,
      RegistrationActor actor,
      Long operatorId,
      String reason,
      Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO office_registration_status_history (registration_id, from_status,"
                + " to_status, changed_by_type, changed_by_operator_id, reason, created_at)"
                + " VALUES (:registrationId, :from, :to, :actor, :operatorId, :reason, :now)")
        .param("registrationId", registrationId)
        .param("from", from == null ? null : from.name())
        .param("to", to.name())
        .param("actor", actor.name())
        .param("operatorId", operatorId)
        .param("reason", reason)
        .param("now", Timestamp.from(now))
        .update();
  }

  public List<HistoryRow> history(long registrationId) {
    return jdbcClient
        .sql(
            "SELECT id, from_status, to_status, changed_by_type, changed_by_operator_id, reason,"
                + " created_at FROM office_registration_status_history"
                + " WHERE registration_id = :id ORDER BY id")
        .param("id", registrationId)
        .query(
            (rs, i) ->
                new HistoryRow(
                    rs.getLong("id"),
                    rs.getString("from_status") == null
                        ? null
                        : RegistrationStatus.valueOf(rs.getString("from_status")),
                    RegistrationStatus.valueOf(rs.getString("to_status")),
                    RegistrationActor.valueOf(rs.getString("changed_by_type")),
                    (Long) rs.getObject("changed_by_operator_id"),
                    rs.getString("reason"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }
}
