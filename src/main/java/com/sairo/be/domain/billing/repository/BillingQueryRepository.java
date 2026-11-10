package com.sairo.be.domain.billing.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Profile("!migrate")
@Repository
public class BillingQueryRepository {

  private static final String PRICE_COLUMNS =
      "id, version, solo_amount, team_first_extra, team_after_four_extra, effective_at, audience";

  private final JdbcClient jdbcClient;

  public BillingQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public int countBillableMembers(long officeId) {
    Integer count =
        jdbcClient
            .sql(
                """
                SELECT count(*) FROM office_membership m
                JOIN app_user u ON u.id = m.user_id
                WHERE m.office_id = :officeId AND m.status = 'APPROVED'
                  AND u.account_status = 'ACTIVE'
                """)
            .param("officeId", officeId)
            .query(Integer.class)
            .single();
    return count;
  }

  public Optional<PriceScheduleRow> findStandardPriceAt(Instant at) {
    return jdbcClient
        .sql(
            "SELECT "
                + PRICE_COLUMNS
                + " FROM price_schedule WHERE audience = 'STANDARD' AND status = 'ACTIVE'"
                + " AND effective_at <= :at ORDER BY effective_at DESC LIMIT 1")
        .param("at", Timestamp.from(at))
        .query(BillingQueryRepository::mapPrice)
        .optional();
  }

  public Optional<PriceScheduleRow> findPriceById(long id) {
    return jdbcClient
        .sql("SELECT " + PRICE_COLUMNS + " FROM price_schedule WHERE id = :id")
        .param("id", id)
        .query(BillingQueryRepository::mapPrice)
        .optional();
  }

  public Optional<ApprovedAgreementRow> findApprovedAgreement(long officeId) {
    return jdbcClient
        .sql(
            """
            SELECT id, price_schedule_id, price_starts_at, price_ends_at
            FROM billing_start_customer_agreement
            WHERE office_id = :officeId AND status = 'APPROVED'
            """)
        .param("officeId", officeId)
        .query(
            (rs, n) ->
                new ApprovedAgreementRow(
                    rs.getLong("id"),
                    rs.getLong("price_schedule_id"),
                    rs.getTimestamp("price_starts_at").toInstant(),
                    rs.getTimestamp("price_ends_at").toInstant()))
        .optional();
  }

  public Optional<TrialEventRow> findActiveGeneralTrialEvent(Instant now) {
    return jdbcClient
        .sql(
            """
            SELECT id, ends_at, free_end_mode, fixed_free_ends_at FROM trial_event
            WHERE audience = 'GENERAL' AND status = 'ACTIVE'
              AND starts_at <= :now AND ends_at > :now
            ORDER BY starts_at DESC LIMIT 1
            """)
        .param("now", Timestamp.from(now))
        .query(
            (rs, n) ->
                new TrialEventRow(
                    rs.getLong("id"),
                    rs.getTimestamp("ends_at").toInstant(),
                    rs.getString("free_end_mode"),
                    rs.getTimestamp("fixed_free_ends_at") == null
                        ? null
                        : rs.getTimestamp("fixed_free_ends_at").toInstant()))
        .optional();
  }

  public int countPendingPriceChangeNotices(long officeId) {
    Integer count =
        jdbcClient
            .sql(
                "SELECT count(*) FROM pricing_change_notice WHERE office_id = :officeId"
                    + " AND decision = 'PENDING'")
            .param("officeId", officeId)
            .query(Integer.class)
            .single();
    return count;
  }

  public long outstandingAmount(long officeId) {
    Long sum =
        jdbcClient
            .sql(
                "SELECT COALESCE(sum(amount), 0) FROM billing_invoice WHERE office_id = :officeId"
                    + " AND status IN ('UNBILLED','PROCESSING','VERIFYING','FAILED')")
            .param("officeId", officeId)
            .query(Long.class)
            .single();
    return sum;
  }

  public boolean hasInProgressPayment(long officeId) {
    Integer count =
        jdbcClient
            .sql(
                "SELECT count(*) FROM billing_invoice WHERE office_id = :officeId"
                    + " AND status IN ('PROCESSING','VERIFYING')")
            .param("officeId", officeId)
            .query(Integer.class)
            .single();
    return count > 0;
  }

  public Optional<LatestInvoiceRow> findLatestInvoice(long officeId) {
    return jdbcClient
        .sql(
            """
            SELECT i.id, i.status, i.next_retry_at,
                   (SELECT a.failure_category FROM billing_charge_attempt a
                    WHERE a.invoice_id = i.id ORDER BY a.attempt_no DESC LIMIT 1) AS failure_category
            FROM billing_invoice i
            WHERE i.office_id = :officeId
            ORDER BY i.billing_period_end DESC, i.id DESC LIMIT 1
            """)
        .param("officeId", officeId)
        .query(
            (rs, n) ->
                new LatestInvoiceRow(
                    rs.getLong("id"),
                    rs.getString("status"),
                    rs.getTimestamp("next_retry_at") == null
                        ? null
                        : rs.getTimestamp("next_retry_at").toInstant(),
                    rs.getString("failure_category")))
        .optional();
  }

  public Map<Long, RefundSums> refundSums(Collection<Long> invoiceIds) {
    Map<Long, RefundSums> result = new HashMap<>();
    if (invoiceIds.isEmpty()) {
      return result;
    }
    List<Object[]> rows =
        jdbcClient
            .sql(
                """
                SELECT invoice_id,
                       COALESCE(sum(refund_amount) FILTER (WHERE status = 'COMPLETED'), 0) AS refunded,
                       COALESCE(sum(refund_amount)
                                FILTER (WHERE status IN ('APPROVED','PROCESSING','VERIFYING')), 0)
                         AS reserved
                FROM refund_request WHERE invoice_id IN (:ids) GROUP BY invoice_id
                """)
            .param("ids", invoiceIds)
            .query((rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)})
            .list();
    for (Object[] row : rows) {
      result.put((Long) row[0], new RefundSums((Long) row[1], (Long) row[2]));
    }
    return result;
  }

  public Optional<Instant> findActiveCancellationEffectiveAt(long subscriptionId) {
    return jdbcClient
        .sql(
            "SELECT effective_at FROM subscription_cancellation WHERE subscription_id ="
                + " :subscriptionId AND revoked_at IS NULL ORDER BY id DESC LIMIT 1")
        .param("subscriptionId", subscriptionId)
        .query((rs, n) -> rs.getTimestamp(1).toInstant())
        .optional();
  }

  public Optional<SupplierRow> findSupplier(int versionNo) {
    return jdbcClient
        .sql(
            "SELECT business_name, representative_name, business_number, address, version_no"
                + " FROM company_service_info WHERE version_no = :versionNo")
        .param("versionNo", versionNo)
        .query(
            (rs, n) ->
                new SupplierRow(
                    rs.getString("business_name"),
                    rs.getString("representative_name"),
                    rs.getString("business_number"),
                    rs.getString("address"),
                    rs.getInt("version_no")))
        .optional();
  }

  public List<TaxDocumentRow> findTaxDocuments(long invoiceId) {
    return jdbcClient
        .sql(
            """
            SELECT d.id, d.document_kind, d.status, d.issued_on, r.receiver_email,
                   d.supply_amount, d.vat_amount
            FROM tax_document d LEFT JOIN tax_invoice_recipient r ON r.id = d.recipient_id
            WHERE d.billing_invoice_id = :invoiceId ORDER BY d.recorded_at DESC, d.id DESC
            """)
        .param("invoiceId", invoiceId)
        .query(
            (rs, n) ->
                new TaxDocumentRow(
                    rs.getLong("id"),
                    rs.getString("document_kind"),
                    rs.getString("status"),
                    rs.getDate("issued_on") == null ? null : rs.getDate("issued_on").toLocalDate(),
                    rs.getString("receiver_email"),
                    (Long) rs.getObject("supply_amount"),
                    (Long) rs.getObject("vat_amount")))
        .list();
  }

  private static PriceScheduleRow mapPrice(java.sql.ResultSet rs, int n)
      throws java.sql.SQLException {
    return new PriceScheduleRow(
        rs.getLong("id"),
        rs.getString("version"),
        rs.getLong("solo_amount"),
        rs.getLong("team_first_extra"),
        rs.getLong("team_after_four_extra"),
        rs.getTimestamp("effective_at").toInstant(),
        rs.getString("audience"));
  }

  public record PriceScheduleRow(
      long id,
      String version,
      long soloAmount,
      long teamFirstExtra,
      long teamAfterFourExtra,
      Instant effectiveAt,
      String audience) {}

  public record ApprovedAgreementRow(
      long id, long priceScheduleId, Instant priceStartsAt, Instant priceEndsAt) {}

  public record TrialEventRow(
      long id, Instant endsAt, String freeEndMode, Instant fixedFreeEndsAt) {}

  public record LatestInvoiceRow(
      long id, String status, Instant nextRetryAt, String lastFailureCategory) {}

  public record RefundSums(long refunded, long reserved) {}

  public record SupplierRow(
      String businessName,
      String representativeName,
      String businessNumber,
      String address,
      int versionNo) {}

  public record TaxDocumentRow(
      long id,
      String documentKind,
      String status,
      java.time.LocalDate issuedOn,
      String receiverEmail,
      Long supplyAmount,
      Long vatAmount) {}
}
