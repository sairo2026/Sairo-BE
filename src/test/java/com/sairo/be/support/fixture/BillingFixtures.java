package com.sairo.be.support.fixture;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

public final class BillingFixtures {

  public static final String PURCHASE_TERMS_VERSION = "2026-12-01";
  public static final String CARD_NOTICE_VERSION = "2026-12-01";
  private static final String HASH = "b".repeat(64);
  private static final AtomicLong SEQUENCE = new AtomicLong(0L);

  private final JdbcTemplate jdbc;
  private Long creatorOperatorId;
  private Long approverOperatorId;

  public BillingFixtures(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long creatorOperator() {
    if (creatorOperatorId == null) {
      creatorOperatorId = operator("creator");
    }
    return creatorOperatorId;
  }

  public long approverOperator() {
    if (approverOperatorId == null) {
      approverOperatorId = operator("approver");
    }
    return approverOperatorId;
  }

  private long operator(String key) {
    return jdbc.queryForObject(
        "INSERT INTO operator_account (kakao_provider_key, name, status, totp_secret_encrypted)"
            + " VALUES (?, ?, 'ACTIVE', 'x') RETURNING id",
        Long.class,
        key + "-" + SEQUENCE.incrementAndGet(),
        "운영자");
  }

  public long price(
      String version, String audience, Instant effectiveAt, String status, long solo) {
    boolean live = "ACTIVE".equals(status) || "ENDED".equals(status);
    return jdbc.queryForObject(
        "INSERT INTO price_schedule (version, solo_amount, team_first_extra,"
            + " team_after_four_extra, effective_at, status, approved_by_operator_id,"
            + " created_by_operator_id, audience) VALUES (?, ?, 10000, 8000, ?, ?, ?, ?, ?)"
            + " RETURNING id",
        Long.class,
        version,
        solo,
        Timestamp.from(effectiveAt),
        status,
        live ? approverOperator() : null,
        creatorOperator(),
        audience);
  }

  public long standardPrice(String version, Instant effectiveAt) {
    return price(version, "STANDARD", effectiveAt, "ACTIVE", 39_900);
  }

  public long trialEvent(
      String audience,
      String mode,
      Instant startsAt,
      Instant endsAt,
      Instant fixedEnd,
      String status) {
    boolean live = "ACTIVE".equals(status) || "ENDED".equals(status);
    return jdbc.queryForObject(
        "INSERT INTO trial_event (name, starts_at, ends_at, status, approved_by_operator_id,"
            + " created_by_operator_id, free_end_mode, fixed_free_ends_at, audience, max_offices)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
        Long.class,
        "이벤트-" + SEQUENCE.incrementAndGet(),
        Timestamp.from(startsAt),
        Timestamp.from(endsAt),
        status,
        live ? approverOperator() : null,
        creatorOperator(),
        mode,
        fixedEnd == null ? null : Timestamp.from(fixedEnd),
        audience,
        "START_CUSTOMER".equals(audience) ? 15 : null);
  }

  public long startCustomerAgreement(
      long officeId,
      long trialEventId,
      long priceScheduleId,
      Instant priceStartsAt,
      Instant priceEndsAt,
      String status) {
    boolean approved = "APPROVED".equals(status);
    boolean revoked = "REVOKED".equals(status);
    return jdbc.queryForObject(
        "INSERT INTO billing_start_customer_agreement (office_id, trial_event_id,"
            + " price_schedule_id, price_starts_at, price_ends_at, status, contracted_on,"
            + " contract_reference, weekly_use_confirmed, monthly_interview_confirmed,"
            + " agreement_text_version, created_by_operator_id, approved_by_operator_id,"
            + " approved_at, revoked_by_operator_id, revoked_at, revoke_reason) VALUES (?, ?, ?,"
            + " ?, ?, ?, current_date, 'ref', true, true, 'v1', ?, ?, ?, ?, ?, ?) RETURNING id",
        Long.class,
        officeId,
        trialEventId,
        priceScheduleId,
        Timestamp.from(priceStartsAt),
        Timestamp.from(priceEndsAt),
        status,
        creatorOperator(),
        approved ? approverOperator() : null,
        approved ? Timestamp.from(Instant.now()) : null,
        revoked ? approverOperator() : null,
        revoked ? Timestamp.from(Instant.now()) : null,
        revoked ? "취소 사유" : null);
  }

  public void purchaseTerms(String version) {
    insertTerms(
        "SUBSCRIPTION_PURCHASE", version, "구독 구매·정기결제 동의", "SEPARATE_CONSENT", true, "PURCHASE");
  }

  public void cardNoticeTerms(String version) {
    insertTerms("CARD_NOTICE", version, "카드 등록 안내", "NOTICE", false, "CARD_REGISTRATION");
  }

  public void insertTerms(
      String code,
      String version,
      String title,
      String category,
      boolean required,
      String context) {
    jdbc.update(
        "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required,"
            + " document_url, content_hash, effective_at, display_context, reconsent_required)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false)",
        code,
        version,
        title,
        category,
        required,
        "/legal/terms/" + code + "/" + version,
        HASH,
        Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
        context);
  }

  public SubscriptionSeed subscription(long officeId) {
    return new SubscriptionSeed(jdbc, officeId);
  }

  public long consent(
      long subscriptionId, long userId, long officeId, long total, long vat, long limit) {
    long agreementId =
        jdbc.queryForObject(
            "INSERT INTO terms_agreement (user_id, terms_code, terms_version, agreed,"
                + " consent_path, office_id) VALUES (?, 'SUBSCRIPTION_PURCHASE', ?, true,"
                + " 'PURCHASE_CONSENT', ?) RETURNING id",
            Long.class,
            userId,
            PURCHASE_TERMS_VERSION,
            officeId);
    Instant now = Instant.now();
    return jdbc.queryForObject(
        "INSERT INTO subscription_purchase_consent (subscription_id, user_id,"
            + " terms_agreement_id, plan, member_count, total_amount_incl_vat, vat_amount,"
            + " amount_limit, period_start, period_end, next_charge_at, agreed) VALUES (?, ?, ?,"
            + " 'SOLO', 1, ?, ?, ?, ?, ?, ?, true) RETURNING id",
        Long.class,
        subscriptionId,
        userId,
        agreementId,
        total,
        vat,
        limit,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(86_400L * 30)),
        Timestamp.from(now.plusSeconds(86_400L * 30)));
  }

  public NoticeSeed notice(long officeId, long subscriptionId, long fromPriceId, long toPriceId) {
    return new NoticeSeed(jdbc, officeId, subscriptionId, fromPriceId, toPriceId);
  }

  public InvoiceSeed invoice(long officeId, long subscriptionId, long consentId) {
    return new InvoiceSeed(jdbc, officeId, subscriptionId, consentId);
  }

  public void billingCustomer(long officeId, String customerKey, boolean withKey) {
    jdbc.update(
        "INSERT INTO billing_customer (office_id, toss_customer_key, billing_key_encrypted,"
            + " billing_key_registered_at, card_issuer, card_last4) VALUES (?, ?, ?, ?, ?, ?)",
        officeId,
        customerKey,
        withKey ? "enc-existing" : null,
        withKey ? Timestamp.from(Instant.now()) : null,
        withKey ? "국민카드" : null,
        withKey ? "1234" : null);
  }

  public static final class SubscriptionSeed {

    private final JdbcTemplate jdbc;
    private final long officeId;
    private String plan = "SOLO";
    private String status = "PRE_PAYMENT";
    private Instant trialStartedAt;
    private Instant trialEndsAt;
    private Long trialEventId;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant cycleAnchorAt;
    private Instant pastDueSince;
    private Instant endedAt;
    private Instant firstBillingScheduledAt;
    private Instant prePaymentSince;

    private SubscriptionSeed(JdbcTemplate jdbc, long officeId) {
      this.jdbc = jdbc;
      this.officeId = officeId;
    }

    public SubscriptionSeed status(String value) {
      this.status = value;
      return this;
    }

    public SubscriptionSeed plan(String value) {
      this.plan = value;
      return this;
    }

    public SubscriptionSeed trial(long eventId, Instant startedAt, Instant endsAt) {
      this.trialEventId = eventId;
      this.trialStartedAt = startedAt;
      this.trialEndsAt = endsAt;
      this.firstBillingScheduledAt = endsAt;
      return this;
    }

    public SubscriptionSeed period(Instant start, Instant end) {
      this.currentPeriodStart = start;
      this.currentPeriodEnd = end;
      this.cycleAnchorAt = start;
      return this;
    }

    public SubscriptionSeed pastDueSince(Instant value) {
      this.pastDueSince = value;
      return this;
    }

    public SubscriptionSeed endedAt(Instant value) {
      this.endedAt = value;
      return this;
    }

    public SubscriptionSeed firstBilling(Instant value) {
      this.firstBillingScheduledAt = value;
      return this;
    }

    public SubscriptionSeed prePaymentSince(Instant value) {
      this.prePaymentSince = value;
      return this;
    }

    public long insert() {
      return jdbc.queryForObject(
          "INSERT INTO subscription (office_id, plan, status, trial_started_at, trial_ends_at,"
              + " trial_event_id, current_period_start, current_period_end, cycle_anchor_at,"
              + " past_due_since, ended_at, first_billing_scheduled_at, pre_payment_since)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
          Long.class,
          officeId,
          plan,
          status,
          ts(trialStartedAt),
          ts(trialEndsAt),
          trialEventId,
          ts(currentPeriodStart),
          ts(currentPeriodEnd),
          ts(cycleAnchorAt),
          ts(pastDueSince),
          ts(endedAt),
          ts(firstBillingScheduledAt),
          ts(prePaymentSince));
    }
  }

  public static final class NoticeSeed {

    private final JdbcTemplate jdbc;
    private final long officeId;
    private final long subscriptionId;
    private final long fromPriceId;
    private final long toPriceId;
    private boolean increase = true;
    private Instant responseDueAt = Instant.now().plusSeconds(86_400L * 20);
    private String decision = "PENDING";
    private Long decidedByMembershipId;
    private Long consentId;

    private NoticeSeed(
        JdbcTemplate jdbc, long officeId, long subscriptionId, long fromPriceId, long toPriceId) {
      this.jdbc = jdbc;
      this.officeId = officeId;
      this.subscriptionId = subscriptionId;
      this.fromPriceId = fromPriceId;
      this.toPriceId = toPriceId;
    }

    public NoticeSeed nonIncrease() {
      this.increase = false;
      this.decision = "NOT_REQUIRED";
      return this;
    }

    public NoticeSeed dueAt(Instant value) {
      this.responseDueAt = value;
      return this;
    }

    public NoticeSeed decided(String value, long membershipId, Long consent) {
      this.decision = value;
      this.decidedByMembershipId = membershipId;
      this.consentId = consent;
      return this;
    }

    public NoticeSeed endedNoConsent() {
      this.decision = "ENDED_NO_CONSENT";
      return this;
    }

    public long insert() {
      Instant notified = responseDueAt.minusSeconds(86_400L * 31);
      Instant effective = notified.plusSeconds(86_400L * 30);
      boolean decidedState = !"PENDING".equals(decision) && !"NOT_REQUIRED".equals(decision);
      return jdbc.queryForObject(
          "INSERT INTO pricing_change_notice (office_id, subscription_id, from_price_schedule_id,"
              + " to_price_schedule_id, change_kind, member_count_snapshot, current_amount_incl_vat,"
              + " new_amount_incl_vat, notified_at, effective_at, applies_from_period_start,"
              + " response_due_at, decision, decided_at, decided_by_membership_id,"
              + " purchase_consent_id) VALUES (?, ?, ?, ?, ?, 3, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
              + " RETURNING id",
          Long.class,
          officeId,
          subscriptionId,
          fromPriceId,
          toPriceId,
          increase ? "INCREASE" : "NON_INCREASE",
          increase ? 49_900 : 59_900,
          increase ? 59_900 : 49_900,
          Timestamp.from(notified),
          Timestamp.from(effective),
          Timestamp.from(responseDueAt),
          Timestamp.from(responseDueAt),
          decision,
          decidedState ? Timestamp.from(Instant.now()) : null,
          decidedByMembershipId,
          consentId);
    }
  }

  public static final class InvoiceSeed {

    private final JdbcTemplate jdbc;
    private final long officeId;
    private final long subscriptionId;
    private final long consentId;
    private long amount = 39_900;
    private String status = "PAID";
    private Instant periodStart = Instant.parse("2027-07-01T00:00:00Z");
    private Instant periodEnd = Instant.parse("2027-07-31T00:00:00Z");
    private Integer supplierInfoVersion;

    private InvoiceSeed(JdbcTemplate jdbc, long officeId, long subscriptionId, long consentId) {
      this.jdbc = jdbc;
      this.officeId = officeId;
      this.subscriptionId = subscriptionId;
      this.consentId = consentId;
    }

    public InvoiceSeed amount(long value) {
      this.amount = value;
      return this;
    }

    public InvoiceSeed status(String value) {
      this.status = value;
      return this;
    }

    public InvoiceSeed period(Instant start, Instant end) {
      this.periodStart = start;
      this.periodEnd = end;
      return this;
    }

    public InvoiceSeed supplierVersion(int value) {
      this.supplierInfoVersion = value;
      return this;
    }

    public long insert() {
      long vat = amount * 1000 / 11000;
      return jdbc.queryForObject(
          "INSERT INTO billing_invoice (office_id, invoice_kind, subscription_id,"
              + " billing_period_start, billing_period_end, amount, member_count_snapshot,"
              + " unit_price_snapshot, vat_included_amount, price_version, consent_version,"
              + " consent_amount_limit, consented_at, purchase_consent_id, status, due_at, paid_at,"
              + " supply_amount, vat_amount, vat_rate_bp_snapshot, supplier_info_version)"
              + " VALUES (?, 'SUBSCRIPTION', ?, ?, ?, ?, 1, ?, ?, '2026-12-STD', ?, ?, now(), ?,"
              + " ?, ?, ?, ?, ?, 1000, ?) RETURNING id",
          Long.class,
          officeId,
          subscriptionId,
          ts(periodStart),
          ts(periodEnd),
          amount,
          amount,
          amount,
          PURCHASE_TERMS_VERSION,
          amount,
          consentId,
          status,
          ts(periodStart),
          "PAID".equals(status) ? ts(periodStart) : null,
          amount - vat,
          vat,
          supplierInfoVersion);
    }
  }

  private static Timestamp ts(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
