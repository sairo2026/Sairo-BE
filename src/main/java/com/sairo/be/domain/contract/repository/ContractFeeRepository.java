package com.sairo.be.domain.contract.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 중개보수 청구(contract_fee)와 변경 사건(contract_fee_revision), 수납·환급 거래(contract_fee_transaction)다. 사건과 거래는
 * 고치거나 지우지 않고 대체 행을 더한다. 청구 행의 금액은 조회용 스냅샷이고 월말결산은 대체되지 않은 사건을 읽는다.
 */
@Profile("!migrate")
@Repository
public class ContractFeeRepository {

  public record Amounts(
      long agreedFeeAmount, long supplyAmount, long taxAmount, long totalAmount) {}

  public record FeeRow(
      Long id,
      Long contractId,
      String payerType,
      Long payerPartyId,
      String payerName,
      boolean vatIncluded,
      Amounts amounts,
      LocalDate billedAt,
      LocalDate dueAt) {}

  public record RevisionRow(
      Long id,
      Long feeId,
      String revisionType,
      LocalDate effectiveDate,
      Amounts amounts,
      String reason,
      Long supersedesRevisionId,
      boolean superseded) {}

  public record TransactionRow(
      Long id,
      Long feeId,
      String transactionType,
      long amount,
      LocalDate actualDate,
      String memo,
      Long supersedesTransactionId,
      String correctionReason,
      Instant recordedAt,
      boolean superseded) {}

  private static final String FEE_COLUMNS =
      "f.id, f.contract_id, f.payer_type, f.payer_party_id, cp.name AS payer_name,"
          + " f.vat_included, f.agreed_fee_amount, f.supply_amount, f.tax_amount, f.total_amount,"
          + " f.billed_at, f.due_at";

  private final JdbcClient jdbcClient;

  public ContractFeeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public Long insertFee(
      Long contractId,
      String payerType,
      Long payerPartyId,
      boolean vatIncluded,
      Amounts amounts,
      LocalDate billedAt,
      LocalDate dueAt,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_fee (contract_id, payer_type, payer_party_id,"
                + " agreed_fee_amount, vat_included, supply_amount, tax_amount, total_amount,"
                + " billed_at, due_at, created_at) VALUES (:contract, :payerType, :payer, :agreed,"
                + " :vat, :supply, :tax, :total, :billed, :due, :now) RETURNING id")
        .param("contract", contractId)
        .param("payerType", payerType)
        .param("payer", payerPartyId)
        .param("agreed", amounts.agreedFeeAmount())
        .param("vat", vatIncluded)
        .param("supply", amounts.supplyAmount())
        .param("tax", amounts.taxAmount())
        .param("total", amounts.totalAmount())
        .param("billed", billedAt)
        .param("due", dueAt)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  public void updateSnapshot(Long feeId, Amounts amounts) {
    jdbcClient
        .sql(
            "UPDATE contract_fee SET agreed_fee_amount = :agreed, supply_amount = :supply,"
                + " tax_amount = :tax, total_amount = :total WHERE id = :id")
        .param("agreed", amounts.agreedFeeAmount())
        .param("supply", amounts.supplyAmount())
        .param("tax", amounts.taxAmount())
        .param("total", amounts.totalAmount())
        .param("id", feeId)
        .update();
  }

  /** 사무소 범위의 청구를 그 계약과 함께 잠근다. 다른 사무소 청구는 비어 있다. */
  public Optional<FeeRow> lock(Long officeId, Long feeId) {
    return jdbcClient
        .sql(
            "SELECT "
                + FEE_COLUMNS
                + " FROM contract_fee f JOIN contract c ON c.id = f.contract_id JOIN"
                + " contract_party cp ON cp.id = f.payer_party_id WHERE f.id = :id AND"
                + " c.office_id = :office FOR UPDATE OF f, c")
        .param("id", feeId)
        .param("office", officeId)
        .query((rs, i) -> fee(rs))
        .optional();
  }

  public List<FeeRow> fees(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT "
                + FEE_COLUMNS
                + " FROM contract_fee f JOIN contract_party cp ON cp.id = f.payer_party_id WHERE"
                + " f.contract_id = :contract ORDER BY f.id")
        .param("contract", contractId)
        .query((rs, i) -> fee(rs))
        .list();
  }

  public Long insertRevision(
      Long feeId,
      String revisionType,
      LocalDate effectiveDate,
      Amounts amounts,
      String reason,
      Long recordedBy,
      Long supersedesRevisionId,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_fee_revision (fee_id, revision_type, effective_date,"
                + " agreed_fee_amount, supply_amount, tax_amount, total_amount, reason,"
                + " recorded_by_membership_id, recorded_at, supersedes_revision_id) VALUES (:fee,"
                + " :type, :effective, :agreed, :supply, :tax, :total, :reason, :by, :now,"
                + " :supersedes) RETURNING id")
        .param("fee", feeId)
        .param("type", revisionType)
        .param("effective", effectiveDate)
        .param("agreed", amounts.agreedFeeAmount())
        .param("supply", amounts.supplyAmount())
        .param("tax", amounts.taxAmount())
        .param("total", amounts.totalAmount())
        .param("reason", reason)
        .param("by", recordedBy)
        .param("now", Timestamp.from(now))
        .param("supersedes", supersedesRevisionId)
        .query(Long.class)
        .single();
  }

  /** 계약의 모든 청구 사건이다. 효력일·ID 오름차순이다. */
  public List<RevisionRow> revisions(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT r.id, r.fee_id, r.revision_type, r.effective_date, r.agreed_fee_amount,"
                + " r.supply_amount, r.tax_amount, r.total_amount, r.reason,"
                + " r.supersedes_revision_id, EXISTS (SELECT 1 FROM contract_fee_revision rx WHERE"
                + " rx.supersedes_revision_id = r.id) AS superseded FROM contract_fee_revision r"
                + " JOIN contract_fee f ON f.id = r.fee_id WHERE f.contract_id = :contract ORDER"
                + " BY r.effective_date, r.id")
        .param("contract", contractId)
        .query(
            (rs, i) ->
                new RevisionRow(
                    rs.getLong("id"),
                    rs.getLong("fee_id"),
                    rs.getString("revision_type"),
                    rs.getObject("effective_date", LocalDate.class),
                    amounts(rs),
                    rs.getString("reason"),
                    rs.getObject("supersedes_revision_id", Long.class),
                    rs.getBoolean("superseded")))
        .list();
  }

  public Long insertTransaction(
      Long feeId,
      String type,
      long amount,
      LocalDate actualDate,
      String memo,
      Long supersedesTransactionId,
      String correctionReason,
      Long recordedBy,
      Instant now) {
    return jdbcClient
        .sql(
            "INSERT INTO contract_fee_transaction (fee_id, transaction_type, amount, actual_date,"
                + " memo, supersedes_transaction_id, correction_reason, recorded_by_membership_id,"
                + " recorded_at) VALUES (:fee, :type, :amount, :actual, :memo, :supersedes,"
                + " :reason, :by, :now) RETURNING id")
        .param("fee", feeId)
        .param("type", type)
        .param("amount", amount)
        .param("actual", actualDate)
        .param("memo", memo)
        .param("supersedes", supersedesTransactionId)
        .param("reason", correctionReason)
        .param("by", recordedBy)
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .single();
  }

  /** 계약의 모든 수납·환급·조정 거래다. 실제 날짜·ID 오름차순이다. */
  public List<TransactionRow> transactions(Long contractId) {
    return jdbcClient
        .sql(
            "SELECT t.id, t.fee_id, t.transaction_type, t.amount, t.actual_date, t.memo,"
                + " t.supersedes_transaction_id, t.correction_reason, t.recorded_at, EXISTS"
                + " (SELECT 1 FROM contract_fee_transaction tx WHERE tx.supersedes_transaction_id"
                + " = t.id) AS superseded FROM contract_fee_transaction t JOIN contract_fee f ON"
                + " f.id = t.fee_id WHERE f.contract_id = :contract ORDER BY t.actual_date, t.id")
        .param("contract", contractId)
        .query(
            (rs, i) ->
                new TransactionRow(
                    rs.getLong("id"),
                    rs.getLong("fee_id"),
                    rs.getString("transaction_type"),
                    rs.getLong("amount"),
                    rs.getObject("actual_date", LocalDate.class),
                    rs.getString("memo"),
                    rs.getObject("supersedes_transaction_id", Long.class),
                    rs.getString("correction_reason"),
                    ContractRepository.instant(rs, "recorded_at"),
                    rs.getBoolean("superseded")))
        .list();
  }

  private static FeeRow fee(ResultSet rs) throws SQLException {
    return new FeeRow(
        rs.getLong("id"),
        rs.getLong("contract_id"),
        rs.getString("payer_type"),
        rs.getLong("payer_party_id"),
        rs.getString("payer_name"),
        rs.getBoolean("vat_included"),
        amounts(rs),
        rs.getObject("billed_at", LocalDate.class),
        rs.getObject("due_at", LocalDate.class));
  }

  private static Amounts amounts(ResultSet rs) throws SQLException {
    return new Amounts(
        rs.getLong("agreed_fee_amount"),
        rs.getLong("supply_amount"),
        rs.getLong("tax_amount"),
        rs.getLong("total_amount"));
  }
}
