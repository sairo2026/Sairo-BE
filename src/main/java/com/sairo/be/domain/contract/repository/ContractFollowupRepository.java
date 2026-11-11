package com.sairo.be.domain.contract.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 계약 후속업무(contract_followup_task)다. 사무소 범위는 계약 표를 거쳐 정한다. */
@Profile("!migrate")
@Repository
public class ContractFollowupRepository {

  public static final String D90 = "D90_FOLLOWUP";

  public record FollowupRow(
      Long id,
      Long contractId,
      String taskType,
      String reportTarget,
      String reportBasis,
      LocalDate targetDate,
      boolean submitted,
      Instant submittedAt) {}

  private static final String COLUMNS =
      "f.id, f.contract_id, f.task_type, f.report_target, f.report_basis,"
          + " CASE WHEN f.report_deadline IS NOT NULL THEN f.report_deadline WHEN f.task_type ="
          + " 'D90_FOLLOWUP' THEN c.lease_end_date END AS target_date, f.submitted,"
          + " f.submitted_at";

  private final JdbcClient jdbcClient;

  public ContractFollowupRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public List<FollowupRow> list(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM contract_followup_task f JOIN contract c ON c.id = f.contract_id WHERE"
                + " f.contract_id = :contract ORDER BY f.created_at, f.id")
        .param("contract", contractId)
        .query((rs, i) -> row(rs))
        .list();
  }

  /** 사무소 범위의 후속업무와 그 계약을 잠근다. 다른 사무소 업무는 비어 있다. */
  public Optional<FollowupRow> lock(Long officeId, Long taskId) {
    return jdbcClient
        .sql(
            "SELECT "
                + COLUMNS
                + " FROM contract_followup_task f JOIN contract c ON c.id = f.contract_id WHERE"
                + " f.id = :id AND c.office_id = :office FOR UPDATE OF f, c")
        .param("id", taskId)
        .param("office", officeId)
        .query((rs, i) -> row(rs))
        .optional();
  }

  public boolean hasType(Long contractId, String taskType) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM contract_followup_task WHERE contract_id ="
                    + " :contract AND task_type = :type)")
            .param("contract", contractId)
            .param("type", taskType)
            .query(Boolean.class)
            .single());
  }

  public void insert(Long contractId, String taskType, Instant now) {
    jdbcClient
        .sql(
            "INSERT INTO contract_followup_task (contract_id, task_type, submitted, created_at)"
                + " VALUES (:contract, :type, false, :now)")
        .param("contract", contractId)
        .param("type", taskType)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** 아직 제출하지 않은 자동 후속업무를 지운다. 사용자가 입력한 값이 없는 행만 대상이다. */
  public int deleteUnsubmitted(Long contractId, String taskType) {
    return jdbcClient
        .sql(
            "DELETE FROM contract_followup_task WHERE contract_id = :contract AND task_type ="
                + " :type AND submitted = false")
        .param("contract", contractId)
        .param("type", taskType)
        .update();
  }

  public void submit(Long taskId, Instant now) {
    jdbcClient
        .sql(
            "UPDATE contract_followup_task SET submitted = true, submitted_at = :now WHERE id ="
                + " :id")
        .param("now", Timestamp.from(now))
        .param("id", taskId)
        .update();
  }

  /** D-90 배치 대상이다. 체결된 전세·월세 가운데 중복 제외되지 않았고 만기가 기준일(오늘+90일) 이하인데 후속업무나 만기 업무가 아직 없는 계약 ID다. */
  public List<Long> d90Targets(LocalDate limit) {
    return jdbcClient
        .sql(
            "SELECT c.id FROM contract c WHERE c.contract_status = 'SIGNED' AND c.deal_type IN"
                + " ('JEONSE','MONTHLY') AND c.duplicate_excluded_at IS NULL AND"
                + " c.lease_end_date <= :limit AND (NOT EXISTS (SELECT 1 FROM"
                + " contract_followup_task f WHERE f.contract_id = c.id AND f.task_type ="
                + " 'D90_FOLLOWUP') OR NOT EXISTS (SELECT 1 FROM task t WHERE t.source_type ="
                + " 'CONTRACT_MATURITY' AND t.source_id = c.id)) ORDER BY c.id")
        .param("limit", limit)
        .query(Long.class)
        .list();
  }

  private static FollowupRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new FollowupRow(
        rs.getLong("id"),
        rs.getLong("contract_id"),
        rs.getString("task_type"),
        rs.getString("report_target"),
        rs.getString("report_basis"),
        rs.getObject("target_date", LocalDate.class),
        rs.getBoolean("submitted"),
        ContractRepository.instant(rs, "submitted_at"));
  }
}
