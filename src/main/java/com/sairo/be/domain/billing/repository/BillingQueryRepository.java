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

  private static final String TRIAL_EVENT_COLUMNS =
      "e.id, e.starts_at, e.ends_at, e.free_end_mode, e.fixed_free_ends_at,"
          + " e.office_created_during, e.first_application_during";

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

  public Optional<TrialEventDetail> findActiveTrialEventAt(String audience, Instant at) {
    return jdbcClient
        .sql(
            "SELECT "
                + TRIAL_EVENT_COLUMNS
                + " FROM trial_event e WHERE e.audience = :audience AND e.status = 'ACTIVE'"
                + " AND e.starts_at <= :at AND e.ends_at > :at ORDER BY e.starts_at DESC LIMIT 1")
        .param("audience", audience)
        .param("at", Timestamp.from(at))
        .query(BillingQueryRepository::mapTrialEvent)
        .optional();
  }

  public Optional<StartCustomerEventRow> findStartCustomerEventFor(long officeId) {
    return jdbcClient
        .sql(
            "SELECT "
                + TRIAL_EVENT_COLUMNS
                + ", a.approved_at FROM billing_start_customer_agreement a"
                + " JOIN trial_event e ON e.id = a.trial_event_id"
                + " WHERE a.office_id = :officeId AND a.status = 'APPROVED'"
                + " AND e.audience = 'START_CUSTOMER' AND e.status = 'ACTIVE'"
                + " AND a.approved_at >= e.starts_at AND a.approved_at < e.ends_at")
        .param("officeId", officeId)
        .query(
            (rs, n) ->
                new StartCustomerEventRow(
                    mapTrialEvent(rs, n), rs.getTimestamp("approved_at").toInstant()))
        .optional();
  }

  public Optional<Long> findInvoiceSubscriptionId(long invoiceId) {
    return jdbcClient
        .sql("SELECT subscription_id FROM billing_invoice WHERE id = :id")
        .param("id", invoiceId)
        .query(Long.class)
        .optional();
  }

  public boolean officeExists(long officeId) {
    return jdbcClient
            .sql("SELECT count(*) FROM office WHERE id = :id")
            .param("id", officeId)
            .query(Integer.class)
            .single()
        > 0;
  }

  public boolean userExists(long userId) {
    return jdbcClient
            .sql("SELECT count(*) FROM app_user WHERE id = :id")
            .param("id", userId)
            .query(Integer.class)
            .single()
        > 0;
  }

  public Optional<Long> findLatestMembershipId(long userId, long officeId) {
    return jdbcClient
        .sql(
            "SELECT id FROM office_membership WHERE user_id = :userId AND office_id = :officeId"
                + " ORDER BY id DESC LIMIT 1")
        .param("userId", userId)
        .param("officeId", officeId)
        .query(Long.class)
        .optional();
  }

  public Optional<Integer> findPublishedSupplierVersion() {
    return jdbcClient
        .sql(
            "SELECT version_no FROM company_service_info WHERE published_at IS NOT NULL"
                + " ORDER BY version_no DESC LIMIT 1")
        .query(Integer.class)
        .optional();
  }

  public int countInvoices(long subscriptionId) {
    Integer count =
        jdbcClient
            .sql("SELECT count(*) FROM billing_invoice WHERE subscription_id = :id")
            .param("id", subscriptionId)
            .query(Integer.class)
            .single();
    return count;
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
                + " :subscriptionId AND revoked_at IS NULL AND effective_at > now()"
                + " ORDER BY id DESC LIMIT 1")
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

  public Optional<Instant> findTrialEventFixedEnd(long trialEventId) {
    return jdbcClient
        .sql("SELECT fixed_free_ends_at FROM trial_event WHERE id = :id")
        .param("id", trialEventId)
        .query((rs, n) -> rs.getTimestamp(1))
        .optional()
        .map(Timestamp::toInstant);
  }

  public Map<Long, String> findPriceVersions(Collection<Long> priceScheduleIds) {
    Map<Long, String> result = new HashMap<>();
    if (priceScheduleIds.isEmpty()) {
      return result;
    }
    jdbcClient
        .sql("SELECT id, version FROM price_schedule WHERE id IN (:ids)")
        .param("ids", priceScheduleIds)
        .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)})
        .list()
        .forEach(row -> result.put((Long) row[0], (String) row[1]));
    return result;
  }

  public Optional<OfficeSummaryRow> findOfficeSummary(long officeId) {
    return jdbcClient
        .sql(
            "SELECT name, representative_name, business_registration_number FROM office"
                + " WHERE id = :officeId")
        .param("officeId", officeId)
        .query(
            (rs, n) ->
                new OfficeSummaryRow(
                    rs.getString("name"),
                    rs.getString("representative_name"),
                    rs.getString("business_registration_number")))
        .optional();
  }

  private static TrialEventDetail mapTrialEvent(java.sql.ResultSet rs, int n)
      throws java.sql.SQLException {
    Timestamp fixed = rs.getTimestamp("fixed_free_ends_at");
    return new TrialEventDetail(
        rs.getLong("id"),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at").toInstant(),
        rs.getString("free_end_mode"),
        fixed == null ? null : fixed.toInstant(),
        rs.getBoolean("office_created_during"),
        rs.getBoolean("first_application_during"));
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

  public record TrialEventDetail(
      long id,
      Instant startsAt,
      Instant endsAt,
      String freeEndMode,
      Instant fixedFreeEndsAt,
      boolean officeCreatedDuring,
      boolean firstApplicationDuring) {}

  public record StartCustomerEventRow(TrialEventDetail event, Instant approvedAt) {}

  public record OfficeSummaryRow(
      String name, String representativeName, String businessRegistrationNumber) {}

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
