package com.sairo.be.domain.contract.repository;

import com.sairo.be.global.support.WithdrawnMemberDisplay;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 계약대금 지급예정(contract_payment_schedule)과 실지급 거래(contract_payment_transaction)다. 거래는 대체 행을 더해 정정한다.
 */
@Profile("!migrate")
@Repository
public class ContractPaymentRepository {

  public record ScheduleRow(
      Long id,
      Long contractId,
      String installmentType,
      int sequenceNo,
      LocalDate scheduledDate,
      long scheduledAmount) {}

  public record PaymentRow(
      Long id,
      Long scheduleId,
      long actualAmount,
      LocalDate actualDate,
      Long confirmedByMembershipId,
      String confirmedByName,
      Instant confirmedAt,
      Long supersedesTransactionId,
      String correctionReason,
      boolean superseded) {}

  private static final String SCHEDULE_COLUMNS =
      "s.id, s.contract_id, s.installment_type, s.sequence_no, s.scheduled_date,"
          + " s.scheduled_amount";

  private final JdbcClient jdbcClient;

  public ContractPaymentRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insertSchedule(
      Long contractId, String type, int sequenceNo, LocalDate date, long amount, Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_payment_schedule (contract_id, installment_type, sequence_no,"
                + " scheduled_date, scheduled_amount, created_at) VALUES (:contract, :type, :seq,"
                + " :date, :amount, :now) RETURNING id")
        .param("contract", contractId)
        .param("type", type)
        .param("seq", sequenceNo)
        .param("date", date)
        .param("amount", amount)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public boolean scheduleExists(Long contractId, String type, int sequenceNo) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "SELECT EXISTS (SELECT 1 FROM contract_payment_schedule WHERE contract_id ="
                    + " :contract AND installment_type = :type AND sequence_no = :seq)")
            .param("contract", contractId)
            .param("type", type)
            .param("seq", sequenceNo)
            .query(Boolean.class)
            .single());
  }

  public List<ScheduleRow> schedules(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT "
                + SCHEDULE_COLUMNS
                + " FROM contract_payment_schedule s WHERE s.contract_id = :contract ORDER BY"
                + " CASE s.installment_type WHEN 'DEPOSIT' THEN 0 WHEN 'INTERIM' THEN 1 ELSE 2"
                + " END, s.sequence_no, s.id")
        .param("contract", contractId)
        .query(
            (rs, i) ->
                new ScheduleRow(
                    rs.getLong("id"),
                    rs.getLong("contract_id"),
                    rs.getString("installment_type"),
                    rs.getInt("sequence_no"),
                    rs.getObject("scheduled_date", LocalDate.class),
                    rs.getLong("scheduled_amount")))
        .list();
  }

  /** 사무소 범위의 지급예정과 그 계약을 잠근다. 다른 사무소 예정은 비어 있다. */
  public Optional<Long> lockScheduleContract(Long officeId, Long scheduleId) {
    return jdbcClient
        .sql(
            "SELECT s.contract_id FROM contract_payment_schedule s JOIN contract c ON c.id ="
                + " s.contract_id WHERE s.id = :id AND c.office_id = :office FOR UPDATE OF s, c")
        .param("id", scheduleId)
        .param("office", officeId)
        .query(Long.class)
        .optional();
  }

  public Long insertPayment(
      Long scheduleId,
      long amount,
      LocalDate actualDate,
      Long supersedes,
      Long confirmedBy,
      String correctionReason,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_payment_transaction (schedule_id, actual_amount, actual_date,"
                + " supersedes_transaction_id, confirmed_by_membership_id, confirmed_at,"
                + " correction_reason) VALUES (:schedule, :amount, :actual, :supersedes, :by,"
                + " :now, :reason) RETURNING id")
        .param("schedule", scheduleId)
        .param("amount", amount)
        .param("actual", actualDate)
        .param("supersedes", supersedes)
        .param("by", confirmedBy)
        .param("now", Timestamp.from(now))
        .param("reason", correctionReason)
        .query(Long.class)
        .single();
  }

  /** 계약의 모든 실지급 거래다. 실지급일·ID 오름차순이다. */
  public List<PaymentRow> payments(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT t.id, t.schedule_id, t.actual_amount, t.actual_date,"
                + " t.confirmed_by_membership_id, t.confirmed_at, t.supersedes_transaction_id,"
                + " t.correction_reason, EXISTS (SELECT 1 FROM contract_payment_transaction tx"
                + " WHERE tx.supersedes_transaction_id = t.id) AS superseded, u.name AS"
                + " member_name, (u.account_status = 'WITHDRAWN') AS withdrawn,"
                + " m.withdrawn_alias_no FROM contract_payment_transaction t JOIN"
                + " contract_payment_schedule s ON s.id = t.schedule_id LEFT JOIN"
                + " office_membership m ON m.id = t.confirmed_by_membership_id LEFT JOIN app_user"
                + " u ON u.id = m.user_id WHERE s.contract_id = :contract ORDER BY t.actual_date,"
                + " t.id")
        .param("contract", contractId)
        .query(
            (rs, i) ->
                new PaymentRow(
                    rs.getLong("id"),
                    rs.getLong("schedule_id"),
                    rs.getLong("actual_amount"),
                    rs.getObject("actual_date", LocalDate.class),
                    rs.getLong("confirmed_by_membership_id"),
                    WithdrawnMemberDisplay.name(
                        rs.getBoolean("withdrawn"),
                        rs.getString("member_name"),
                        rs.getObject("withdrawn_alias_no", Integer.class)),
                    ContractRepository.instant(rs, "confirmed_at"),
                    rs.getObject("supersedes_transaction_id", Long.class),
                    rs.getString("correction_reason"),
                    rs.getBoolean("superseded")))
        .list();
  }
}
