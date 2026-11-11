package com.sairo.be.domain.billing.service;

import com.sairo.be.global.port.PaymentGateway;
import com.sairo.be.global.port.PaymentGateway.PaymentStatus;
import com.sairo.be.global.port.PaymentGateway.TransactionSnapshot;
import com.sairo.be.global.support.TimeRules;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * KST 영업일 하루의 승인·취소 거래를 PG 조회와 대사해 billing_reconciliation_entry에 남긴다. 같은 영업일은 system_job_run 키로 한
 * 번만 만든다. 대사만으로 승인액을 만들거나 다시 청구하지 않는다.
 */
@Slf4j
@Profile("!migrate")
@Service
public class BillingReconciliationService {

  static final String JOB_NAME = "billing-reconciliation";

  private final JdbcClient jdbcClient;
  private final PaymentGateway paymentGateway;
  private final SystemJobRunService jobRunService;
  private final TransactionTemplate transactionTemplate;

  public BillingReconciliationService(
      JdbcClient jdbcClient,
      PaymentGateway paymentGateway,
      SystemJobRunService jobRunService,
      TransactionTemplate transactionTemplate) {
    this.jdbcClient = jdbcClient;
    this.paymentGateway = paymentGateway;
    this.jobRunService = jobRunService;
    this.transactionTemplate = transactionTemplate;
  }

  /** 지금 시각 기준 KST 전일을 대사한다. 이미 끝난 영업일이면 아무것도 하지 않는다. */
  public boolean runForPreviousDay(Instant now) {
    LocalDate businessDate = TimeRules.displayDate(now).minusDays(1);
    return run(businessDate);
  }

  public boolean run(LocalDate businessDate) {
    return jobRunService.runOnce(
        JOB_NAME,
        null,
        JOB_NAME + ":" + businessDate,
        TimeRules.endOfDisplayDayExclusive(businessDate),
        () -> reconcile(businessDate));
  }

  void reconcile(LocalDate businessDate) {
    Instant from = TimeRules.startOfDisplayDay(businessDate);
    Instant to = TimeRules.endOfDisplayDayExclusive(businessDate);
    List<Entry> entries = new java.util.ArrayList<>();
    for (ChargeRow charge : charges(from, to)) {
      TransactionSnapshot snapshot = lookup(charge.orderId());
      Entry entry = chargeEntry(charge, snapshot);
      if (entry != null) {
        entries.add(entry);
      }
    }
    for (RefundRow refund : refunds(from, to)) {
      TransactionSnapshot snapshot =
          refund.orderId() == null
              ? new TransactionSnapshot(PaymentStatus.FAILED, null, null)
              : lookup(refund.orderId());
      entries.add(refundEntry(refund, snapshot));
    }
    transactionTemplate.executeWithoutResult(
        status -> entries.forEach(entry -> insert(businessDate, entry)));
  }

  /** 내부 성공은 PG 승인액과 비교하고, 내부 실패인데 PG에 승인이 있으면 PG에만 있는 거래다. 둘 다 승인이 없으면 남기지 않는다. */
  static Entry chargeEntry(ChargeRow charge, TransactionSnapshot snapshot) {
    boolean internalSuccess = "SUCCESS".equals(charge.result());
    Long pgAmount = snapshot.approvedAmount();
    String status;
    if (snapshot.status() == PaymentStatus.VERIFYING) {
      status = "VERIFYING";
    } else if (snapshot.status() == PaymentStatus.SUCCEEDED) {
      if (!internalSuccess) {
        status = "PG_ONLY";
      } else if (pgAmount == null) {
        status = "VERIFYING";
      } else {
        status = pgAmount == charge.amount() ? "MATCHED" : "AMOUNT_MISMATCH";
      }
    } else if (internalSuccess) {
      status = "INTERNAL_ONLY";
    } else {
      return null;
    }
    return new Entry(
        charge.orderId(),
        charge.invoiceId(),
        null,
        charge.officeId(),
        internalSuccess ? charge.amount() : null,
        pgAmount,
        status);
  }

  /** 환불은 청구서의 완료 환불 누계와 PG 취소 누계를 비교한다. */
  static Entry refundEntry(RefundRow refund, TransactionSnapshot snapshot) {
    Long pgCancelled = snapshot.cancelledAmount();
    String status;
    if (snapshot.status() == PaymentStatus.VERIFYING) {
      status = "VERIFYING";
    } else if (snapshot.status() != PaymentStatus.SUCCEEDED) {
      status = "INTERNAL_ONLY";
    } else if (pgCancelled == null) {
      status = "VERIFYING";
    } else {
      status = pgCancelled == refund.completedTotal() ? "MATCHED" : "AMOUNT_MISMATCH";
    }
    return new Entry(
        refund.orderId(),
        refund.invoiceId(),
        refund.refundId(),
        refund.officeId(),
        refund.completedTotal(),
        pgCancelled,
        status);
  }

  private TransactionSnapshot lookup(String orderId) {
    try {
      return paymentGateway.lookup(orderId);
    } catch (RuntimeException e) {
      log.warn("대사 거래 조회에 실패했습니다. orderId={}", orderId, e);
      return new TransactionSnapshot(PaymentStatus.VERIFYING, null, null);
    }
  }

  private List<ChargeRow> charges(Instant from, Instant to) {
    return jdbcClient
        .sql(
            "SELECT a.pg_order_id, a.result, i.id AS invoice_id, i.office_id, i.amount"
                + " FROM billing_charge_attempt a JOIN billing_invoice i ON i.id = a.invoice_id"
                + " WHERE a.result IN ('SUCCESS','FAILED') AND a.resolved_at >= :from"
                + " AND a.resolved_at < :to ORDER BY a.id")
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(
            (rs, n) ->
                new ChargeRow(
                    rs.getString("pg_order_id"),
                    rs.getString("result"),
                    rs.getLong("invoice_id"),
                    rs.getLong("office_id"),
                    rs.getLong("amount")))
        .list();
  }

  private List<RefundRow> refunds(Instant from, Instant to) {
    return jdbcClient
        .sql(
            "SELECT r.id, r.invoice_id, r.office_id,"
                + " (SELECT a.pg_order_id FROM billing_charge_attempt a WHERE a.invoice_id ="
                + " r.invoice_id AND a.result = 'SUCCESS' ORDER BY a.attempt_no DESC LIMIT 1)"
                + " AS order_id,"
                + " (SELECT coalesce(sum(c.refund_amount), 0) FROM refund_request c WHERE"
                + " c.invoice_id = r.invoice_id AND c.status = 'COMPLETED') AS completed_total"
                + " FROM refund_request r WHERE r.status = 'COMPLETED' AND r.resolved_at >= :from"
                + " AND r.resolved_at < :to ORDER BY r.id")
        .param("from", Timestamp.from(from))
        .param("to", Timestamp.from(to))
        .query(
            (rs, n) ->
                new RefundRow(
                    rs.getLong("id"),
                    rs.getLong("invoice_id"),
                    rs.getLong("office_id"),
                    rs.getString("order_id"),
                    rs.getLong("completed_total")))
        .list();
  }

  private void insert(LocalDate businessDate, Entry entry) {
    jdbcClient
        .sql(
            "INSERT INTO billing_reconciliation_entry (business_date, source_type, pg_order_id,"
                + " invoice_id, refund_request_id, office_id, internal_amount, pg_amount, status)"
                + " VALUES (:date, 'PG_QUERY', :orderId, :invoiceId, :refundId, :officeId,"
                + " :internal, :pg, :status)")
        .param("date", businessDate)
        .param("orderId", entry.orderId())
        .param("invoiceId", entry.invoiceId())
        .param("refundId", entry.refundId())
        .param("officeId", entry.officeId())
        .param("internal", entry.internalAmount())
        .param("pg", entry.pgAmount())
        .param("status", entry.status())
        .update();
  }

  record ChargeRow(String orderId, String result, long invoiceId, long officeId, long amount) {}

  record RefundRow(
      long refundId, long invoiceId, long officeId, String orderId, long completedTotal) {}

  record Entry(
      String orderId,
      Long invoiceId,
      Long refundId,
      Long officeId,
      Long internalAmount,
      Long pgAmount,
      String status) {}
}
