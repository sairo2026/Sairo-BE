package com.sairo.be.domain.contract.repository;

import com.sairo.be.domain.contract.entity.StatusHistoryRow;
import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 계약 상태 이력(contract_status_history)과 핵심정보 버전(contract_revision)이다. 둘 다 고치거나 지우지 않고 더하기만 한다. */
@Profile("!migrate")
@Repository
public class ContractHistoryRepository {

  public record HistoryView(StatusHistoryRow row, String changedByName) {}

  public record RevisionRow(
      Long id,
      int revisionNo,
      String revisionType,
      String previousSnapshot,
      String currentSnapshot,
      Instant effectiveAt,
      Instant recordedAt,
      Long recordedByMembershipId,
      String reason,
      Long supersedesRevisionId) {}

  private static final String HISTORY_COLUMNS =
      "h.id, h.track, h.from_status, h.to_status, h.reason, h.changed_by_membership_id,"
          + " h.effective_at, h.supersedes_history_id, h.created_at";

  private static final String VALID =
      " AND NOT EXISTS (SELECT 1 FROM contract_status_history hx"
          + " WHERE hx.supersedes_history_id = h.id)";

  private static final String REVISION_COLUMNS =
      "r.id, r.revision_no, r.revision_type, CAST(r.previous_snapshot AS text) AS previous,"
          + " CAST(r.current_snapshot AS text) AS current, r.effective_at, r.recorded_at,"
          + " r.recorded_by_membership_id, r.reason, r.supersedes_revision_id";

  private final JdbcClient jdbcClient;

  public ContractHistoryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insertStatus(
      Long contractId,
      String track,
      String fromStatus,
      String toStatus,
      String reason,
      Long changedBy,
      Instant effectiveAt,
      Long supersedesHistoryId,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_status_history (contract_id, track, from_status, to_status,"
                + " reason, changed_by_membership_id, effective_at, supersedes_history_id,"
                + " created_at) VALUES (:contract, :track, :from, :to, :reason, :by, :effective,"
                + " :supersedes, :now) RETURNING id")
        .param("contract", contractId)
        .param("track", track)
        .param("from", fromStatus)
        .param("to", toStatus)
        .param("reason", reason)
        .param("by", changedBy)
        .param("effective", Timestamp.from(effectiveAt))
        .param("supersedes", supersedesHistoryId)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public Optional<StatusHistoryRow> findStatus(Long contractId, Long historyId) {
    return jdbcClient
        .sql(
            "SELECT "
                + HISTORY_COLUMNS
                + " FROM contract_status_history h WHERE h.id = :id AND h.contract_id = :contract")
        .param("id", historyId)
        .param("contract", contractId)
        .query((rs, i) -> history(rs))
        .optional();
  }

  public boolean isSuperseded(Long historyId) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM contract_status_history WHERE supersedes_history_id"
                    + " = :id)")
            .param("id", historyId)
            .query(Boolean.class)
            .single());
  }

  /** 대체되지 않은 계약 트랙 사건을 효력시각 순으로 돌려준다. */
  public List<StatusHistoryRow> validContractEvents(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT "
                + HISTORY_COLUMNS
                + " FROM contract_status_history h WHERE h.contract_id = :contract AND h.track ="
                + " 'CONTRACT'"
                + VALID
                + " ORDER BY h.effective_at, h.id")
        .param("contract", contractId)
        .query((rs, i) -> history(rs))
        .list();
  }

  public long countStatus(Long contractId) {
    return jdbcClient
        .sql("SELECT count(*) FROM contract_status_history WHERE contract_id = :id")
        .param("id", contractId)
        .query(Long.class)
        .single();
  }

  public List<HistoryView> pageStatus(Long contractId, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT "
                + HISTORY_COLUMNS
                + ", u.name AS member_name, (u.account_status = 'WITHDRAWN') AS withdrawn,"
                + " m.withdrawn_alias_no FROM contract_status_history h LEFT JOIN"
                + " office_membership m ON m.id = h.changed_by_membership_id LEFT JOIN app_user u"
                + " ON u.id = m.user_id WHERE h.contract_id = :contract ORDER BY h.effective_at,"
                + " h.id LIMIT :limit OFFSET :offset")
        .param("contract", contractId)
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, i) -> {
              StatusHistoryRow row = history(rs);
              String name =
                  row.changedByMembershipId() == null
                      ? null
                      : WithdrawnMemberDisplay.name(
                          rs.getBoolean("withdrawn"),
                          rs.getString("member_name"),
                          rs.getObject("withdrawn_alias_no", Integer.class));
              return new HistoryView(row, name);
            })
        .list();
  }

  public Long insertRevision(
      Long contractId,
      int revisionNo,
      String revisionType,
      String previousSnapshot,
      String currentSnapshot,
      Instant effectiveAt,
      Long recordedBy,
      String reason,
      Long supersedesRevisionId,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_revision (contract_id, revision_no, revision_type,"
                + " previous_snapshot, current_snapshot, effective_at, recorded_at,"
                + " recorded_by_membership_id, reason, supersedes_revision_id) VALUES (:contract,"
                + " :no, :type, CAST(:previous AS jsonb), CAST(:current AS jsonb), :effective,"
                + " :now, :by, :reason, :supersedes) RETURNING id")
        .param("contract", contractId)
        .param("no", revisionNo)
        .param("type", revisionType)
        .param("previous", previousSnapshot)
        .param("current", currentSnapshot)
        .param("effective", Timestamp.from(effectiveAt))
        .param("now", Timestamp.from(now))
        .param("by", recordedBy)
        .param("reason", reason)
        .param("supersedes", supersedesRevisionId)
        .query(Long.class)
        .single();
  }

  public Optional<RevisionRow> latestRevision(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT "
                + REVISION_COLUMNS
                + " FROM contract_revision r WHERE r.contract_id = :contract ORDER BY"
                + " r.revision_no DESC LIMIT 1")
        .param("contract", contractId)
        .query((rs, i) -> revision(rs))
        .optional();
  }

  public Optional<RevisionRow> findRevision(Long revisionId) {
    return jdbcClient
        .sql("SELECT " + REVISION_COLUMNS + " FROM contract_revision r WHERE r.id = :id")
        .param("id", revisionId)
        .query((rs, i) -> revision(rs))
        .optional();
  }

  public long countRevisions(Long contractId) {
    return jdbcClient
        .sql("SELECT count(*) FROM contract_revision WHERE contract_id = :id")
        .param("id", contractId)
        .query(Long.class)
        .single();
  }

  public List<RevisionRow> pageRevisions(Long contractId, int limit, long offset) {
    return jdbcClient
        .sql(
            "SELECT "
                + REVISION_COLUMNS
                + " FROM contract_revision r WHERE r.contract_id = :contract ORDER BY"
                + " r.revision_no LIMIT :limit OFFSET :offset")
        .param("contract", contractId)
        .param("limit", limit)
        .param("offset", offset)
        .query((rs, i) -> revision(rs))
        .list();
  }

  private static StatusHistoryRow history(ResultSet rs) throws SQLException {
    return new StatusHistoryRow(
        rs.getLong("id"),
        rs.getString("track"),
        rs.getString("from_status"),
        rs.getString("to_status"),
        rs.getString("reason"),
        rs.getObject("changed_by_membership_id", Long.class),
        ContractRepository.instant(rs, "effective_at"),
        rs.getObject("supersedes_history_id", Long.class),
        ContractRepository.instant(rs, "created_at"));
  }

  private static RevisionRow revision(ResultSet rs) throws SQLException {
    return new RevisionRow(
        rs.getLong("id"),
        rs.getInt("revision_no"),
        rs.getString("revision_type"),
        rs.getString("previous"),
        rs.getString("current"),
        ContractRepository.instant(rs, "effective_at"),
        ContractRepository.instant(rs, "recorded_at"),
        rs.getLong("recorded_by_membership_id"),
        rs.getString("reason"),
        rs.getObject("supersedes_revision_id", Long.class));
  }
}
