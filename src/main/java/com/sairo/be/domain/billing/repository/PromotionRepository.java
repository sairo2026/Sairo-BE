package com.sairo.be.domain.billing.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Internal zero-charge transactions are deliberately separate from PG invoices. */
@Profile("!migrate")
@Repository
public class PromotionRepository {
  private final JdbcClient jdbc;

  public PromotionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Campaign> campaign(long id) {
    return jdbc.sql(
            "SELECT id, name, code, application_starts_at, application_ends_at, free_months,"
                + " status, max_offices, created_by_operator_id, self_redeemable"
                + " FROM promotion_campaign WHERE id = :id")
        .param("id", id)
        .query(PromotionRepository::campaignRow)
        .optional();
  }

  public List<Campaign> campaigns() {
    return jdbc.sql(
            "SELECT id, name, code, application_starts_at, application_ends_at, free_months,"
                + " status, max_offices, created_by_operator_id, self_redeemable FROM promotion_campaign"
                + " ORDER BY id DESC LIMIT 100")
        .query(PromotionRepository::campaignRow)
        .list();
  }

  public Optional<Campaign> lockCampaign(long id) {
    return jdbc.sql(
            "SELECT id, name, code, application_starts_at, application_ends_at, free_months,"
                + " status, max_offices, created_by_operator_id, self_redeemable"
                + " FROM promotion_campaign WHERE id = :id"
                + " FOR UPDATE")
        .param("id", id)
        .query(PromotionRepository::campaignRow)
        .optional();
  }

  public Optional<Campaign> lockSelfRedeemableCampaign(String code) {
    return jdbc.sql(
            "SELECT id, name, code, application_starts_at, application_ends_at, free_months,"
                + " status, max_offices, created_by_operator_id, self_redeemable"
                + " FROM promotion_campaign WHERE code = :code AND self_redeemable = TRUE FOR UPDATE")
        .param("code", code)
        .query(PromotionRepository::campaignRow)
        .optional();
  }

  public long createCampaign(
      String name,
      String code,
      Instant startsAt,
      Instant endsAt,
      Integer freeMonths,
      Integer maxOffices,
      long operatorId,
      boolean selfRedeemable) {
    return jdbc.sql(
            "INSERT INTO promotion_campaign (name, code, application_starts_at,"
                + " application_ends_at, free_months, status, max_offices, created_by_operator_id,"
                + " self_redeemable)"
                + " VALUES (:name, :code, :starts, :ends, :months, 'DRAFT', :maximum, :operator, :redeemable)"
                + " RETURNING id")
        .param("name", name)
        .param("code", code)
        .param("starts", Timestamp.from(startsAt))
        .param("ends", Timestamp.from(endsAt))
        .param("months", freeMonths)
        .param("maximum", maxOffices)
        .param("operator", operatorId)
        .param("redeemable", selfRedeemable)
        .query(Long.class)
        .single();
  }

  public void activateCampaign(long id, long operatorId) {
    jdbc.sql(
            "UPDATE promotion_campaign SET status = 'ACTIVE', activated_by_operator_id ="
                + " :operator WHERE id = :id AND status = 'DRAFT'")
        .param("operator", operatorId)
        .param("id", id)
        .update();
  }

  public void endCampaign(long id) {
    jdbc.sql("UPDATE promotion_campaign SET status = 'ENDED' WHERE id = :id AND status = 'ACTIVE'")
        .param("id", id)
        .update();
  }

  public long grantsCount(long campaignId) {
    return jdbc.sql("SELECT count(*) FROM promotion_grant WHERE campaign_id = :campaign")
        .param("campaign", campaignId)
        .query(Long.class)
        .single();
  }

  public boolean hasLiveGrant(long officeId) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM promotion_grant WHERE office_id = :office"
                + " AND status IN ('ACTIVE', 'SUSPENDED', 'RECONCILIATION_REQUIRED'))")
        .param("office", officeId)
        .query(Boolean.class)
        .single();
  }

  public boolean isApprovedOwnerMembership(long membershipId, long officeId) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM office_membership WHERE id = :membership"
                + " AND office_id = :office AND role = 'OWNER' AND status = 'APPROVED')")
        .param("membership", membershipId)
        .param("office", officeId)
        .query(Boolean.class)
        .single();
  }

  public boolean hasGrantForSubscription(long subscriptionId) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM promotion_grant WHERE subscription_id = :subscription"
                + " AND status IN ('ACTIVE', 'SUSPENDED', 'RECONCILIATION_REQUIRED', 'CONVERSION_PENDING'))")
        .param("subscription", subscriptionId)
        .query(Boolean.class)
        .single();
  }

  public boolean hasAnyGrantForSubscription(long subscriptionId) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM promotion_grant WHERE subscription_id = :subscription)")
        .param("subscription", subscriptionId)
        .query(Boolean.class)
        .single();
  }

  public boolean requiresReconciliation(long subscriptionId) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM promotion_grant WHERE subscription_id = :subscription"
                + " AND status = 'RECONCILIATION_REQUIRED')")
        .param("subscription", subscriptionId)
        .query(Boolean.class)
        .single();
  }

  public Optional<Instant> conversionBoundary(long subscriptionId) {
    return jdbc.sql(
            "SELECT ended_at FROM promotion_grant WHERE subscription_id = :subscription"
                + " AND status IN ('CONVERSION_PENDING', 'REVOKED')"
                + " ORDER BY ended_at DESC, id DESC LIMIT 1")
        .param("subscription", subscriptionId)
        .query((rs, ignored) -> rs.getTimestamp("ended_at").toInstant())
        .optional();
  }

  public long createGrant(
      long campaignId,
      long officeId,
      long subscriptionId,
      Instant start,
      Instant freeEnd,
      Instant nextPeriodStart,
      Long operatorId,
      Long membershipId) {
    return jdbc.sql(
            "INSERT INTO promotion_grant (campaign_id, office_id, subscription_id, status,"
                + " starts_at, free_ends_at, billing_anchor_at, next_period_start,"
                + " granted_by_operator_id, redeemed_by_membership_id)"
                + " VALUES (:campaign, :office, :subscription, 'ACTIVE',"
                + " :start, :freeEnd, :start, :next, :operator, :member) RETURNING id")
        .param("campaign", campaignId)
        .param("office", officeId)
        .param("subscription", subscriptionId)
        .param("start", Timestamp.from(start))
        .param("freeEnd", freeEnd == null ? null : Timestamp.from(freeEnd))
        .param("next", Timestamp.from(nextPeriodStart))
        .param("operator", operatorId)
        .param("member", membershipId)
        .query(Long.class)
        .single();
  }

  public Optional<Grant> lockGrant(long id) {
    return jdbc.sql(
            "SELECT id, campaign_id, office_id, subscription_id, status, starts_at,"
                + " free_ends_at, billing_anchor_at, next_period_start FROM promotion_grant"
                + " WHERE id = :id FOR UPDATE")
        .param("id", id)
        .query(PromotionRepository::grantRow)
        .optional();
  }

  public Optional<Grant> grant(long id) {
    return jdbc.sql(
            "SELECT id, campaign_id, office_id, subscription_id, status, starts_at,"
                + " free_ends_at, billing_anchor_at, next_period_start FROM promotion_grant"
                + " WHERE id = :id")
        .param("id", id)
        .query(PromotionRepository::grantRow)
        .optional();
  }

  public Optional<Grant> activeGrantForOffice(long officeId) {
    return jdbc.sql(
            "SELECT id, campaign_id, office_id, subscription_id, status, starts_at,"
                + " free_ends_at, billing_anchor_at, next_period_start FROM promotion_grant"
                + " WHERE office_id = :office AND status = 'ACTIVE'")
        .param("office", officeId)
        .query(PromotionRepository::grantRow)
        .optional();
  }

  public Optional<Grant> liveGrantForSubscription(long subscriptionId) {
    return jdbc.sql(
            "SELECT id, campaign_id, office_id, subscription_id, status, starts_at,"
                + " free_ends_at, billing_anchor_at, next_period_start FROM promotion_grant"
                + " WHERE subscription_id = :subscription"
                + " AND status IN ('ACTIVE', 'SUSPENDED', 'RECONCILIATION_REQUIRED')"
                + " ORDER BY id DESC LIMIT 1 FOR UPDATE")
        .param("subscription", subscriptionId)
        .query(PromotionRepository::grantRow)
        .optional();
  }

  public Optional<Grant> currentOrPendingGrantForOffice(long officeId) {
    return jdbc.sql(
            "SELECT id, campaign_id, office_id, subscription_id, status, starts_at,"
                + " free_ends_at, billing_anchor_at, next_period_start FROM promotion_grant"
                + " WHERE office_id = :office AND status IN"
                + " ('ACTIVE', 'SUSPENDED', 'RECONCILIATION_REQUIRED', 'CONVERSION_PENDING')"
                + " ORDER BY id DESC LIMIT 1")
        .param("office", officeId)
        .query(PromotionRepository::grantRow)
        .optional();
  }

  public List<Grant> grants() {
    return jdbc.sql(
            "SELECT id, campaign_id, office_id, subscription_id, status, starts_at,"
                + " free_ends_at, billing_anchor_at, next_period_start FROM promotion_grant"
                + " ORDER BY id DESC LIMIT 100")
        .query(PromotionRepository::grantRow)
        .list();
  }

  public List<Long> dueGrants(Instant now) {
    return jdbc.sql(
            "SELECT id FROM promotion_grant WHERE status = 'ACTIVE' AND next_period_start <= :now"
                + " ORDER BY next_period_start, id LIMIT 200")
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .list();
  }

  public List<Long> grantsWithEffectiveCancellation(Instant now) {
    return jdbc.sql(
            "SELECT g.id FROM promotion_grant g JOIN subscription_cancellation c"
                + " ON c.subscription_id = g.subscription_id AND c.revoked_at IS NULL"
                + " WHERE g.status IN ('ACTIVE', 'SUSPENDED', 'RECONCILIATION_REQUIRED')"
                + " AND c.effective_at <= :now ORDER BY c.effective_at, g.id LIMIT 200")
        .param("now", Timestamp.from(now))
        .query(Long.class)
        .list();
  }

  public void advanceGrant(long id, Instant nextStart) {
    jdbc.sql(
            "UPDATE promotion_grant SET next_period_start = :next, version = version + 1 WHERE id = :id")
        .param("next", Timestamp.from(nextStart))
        .param("id", id)
        .update();
  }

  public void finishGrant(long id, String status, Instant at) {
    jdbc.sql(
            "UPDATE promotion_grant SET status = :status, ended_at = :at, version = version + 1"
                + " WHERE id = :id")
        .param("status", status)
        .param("at", Timestamp.from(at))
        .param("id", id)
        .update();
  }

  public void changeGrantStatus(long id, String status) {
    jdbc.sql("UPDATE promotion_grant SET status = :status, version = version + 1 WHERE id = :id")
        .param("status", status)
        .param("id", id)
        .update();
  }

  public void markReconciledPeriod(long periodId, long operatorId, String reason) {
    jdbc.sql(
            "UPDATE promotion_period SET record_source = 'RECONCILED',"
                + " reconciled_by_operator_id = :operator, reconciliation_reason = :reason"
                + " WHERE id = :period AND record_source = 'AUTOMATIC'")
        .param("operator", operatorId)
        .param("reason", reason)
        .param("period", periodId)
        .update();
  }

  public boolean periodExists(long subscriptionId, Instant periodStart) {
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM promotion_period WHERE subscription_id = :subscription"
                + " AND period_start = :start)")
        .param("subscription", subscriptionId)
        .param("start", Timestamp.from(periodStart))
        .query(Boolean.class)
        .single();
  }

  public long createPeriod(
      Grant grant,
      Instant start,
      Instant end,
      long listAmount,
      String priceVersion,
      int memberCount) {
    return jdbc.sql(
            "INSERT INTO promotion_period (grant_id, office_id, subscription_id, period_start,"
                + " period_end, list_amount, discount_amount, charged_amount, price_version,"
                + " member_count_snapshot, office_name_snapshot, plan_snapshot)"
                + " VALUES (:grant, :office, :subscription, :start, :end,"
                + " :list, :list, 0, :version, :members,"
                + " (SELECT name FROM office WHERE id = :office),"
                + " (SELECT plan FROM subscription WHERE id = :subscription)) RETURNING id")
        .param("grant", grant.id())
        .param("office", grant.officeId())
        .param("subscription", grant.subscriptionId())
        .param("start", Timestamp.from(start))
        .param("end", Timestamp.from(end))
        .param("list", listAmount)
        .param("version", priceVersion)
        .param("members", memberCount)
        .query(Long.class)
        .single();
  }

  public List<Period> periods(long officeId) {
    return jdbc.sql(
            "SELECT p.id, p.period_start, p.period_end, p.list_amount, p.discount_amount,"
                + " p.charged_amount, p.price_version, p.created_at, p.office_name_snapshot,"
                + " p.plan_snapshot, g.free_ends_at, c.name AS campaign_name,"
                + " CASE WHEN c.self_redeemable THEN '****' || right(c.code, 4)"
                + " ELSE c.code END AS campaign_code FROM promotion_period p"
                + " JOIN promotion_grant g ON g.id = p.grant_id"
                + " JOIN promotion_campaign c ON c.id = g.campaign_id"
                + " WHERE p.office_id = :office ORDER BY p.period_start DESC LIMIT 1000")
        .param("office", officeId)
        .query(
            (rs, rowNum) ->
                new Period(
                    rs.getLong("id"),
                    rs.getTimestamp("period_start").toInstant(),
                    rs.getTimestamp("period_end").toInstant(),
                    rs.getLong("list_amount"),
                    rs.getLong("discount_amount"),
                    rs.getLong("charged_amount"),
                    rs.getString("price_version"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("campaign_name"),
                    rs.getString("campaign_code"),
                    rs.getString("office_name_snapshot"),
                    rs.getString("plan_snapshot"),
                    rs.getTimestamp("free_ends_at").toInstant()))
        .list();
  }

  private static Campaign campaignRow(ResultSet rs, int ignored) throws SQLException {
    return new Campaign(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("code"),
        rs.getTimestamp("application_starts_at").toInstant(),
        rs.getTimestamp("application_ends_at").toInstant(),
        (Integer) rs.getObject("free_months"),
        rs.getString("status"),
        (Integer) rs.getObject("max_offices"),
        rs.getLong("created_by_operator_id"),
        rs.getBoolean("self_redeemable"));
  }

  private static Grant grantRow(ResultSet rs, int ignored) throws SQLException {
    var freeEnd = rs.getTimestamp("free_ends_at");
    return new Grant(
        rs.getLong("id"),
        rs.getLong("campaign_id"),
        rs.getLong("office_id"),
        rs.getLong("subscription_id"),
        rs.getString("status"),
        rs.getTimestamp("starts_at").toInstant(),
        freeEnd == null ? null : freeEnd.toInstant(),
        rs.getTimestamp("billing_anchor_at").toInstant(),
        rs.getTimestamp("next_period_start").toInstant());
  }

  public record Campaign(
      long id,
      String name,
      String code,
      Instant startsAt,
      Instant endsAt,
      Integer freeMonths,
      String status,
      Integer maxOffices,
      long creatorId,
      boolean selfRedeemable) {}

  public record Grant(
      long id,
      long campaignId,
      long officeId,
      long subscriptionId,
      String status,
      Instant startsAt,
      Instant freeEndsAt,
      Instant anchor,
      Instant nextPeriodStart) {}

  public record Period(
      long id,
      Instant periodStart,
      Instant periodEnd,
      long listAmount,
      long discountAmount,
      long chargedAmount,
      String priceVersion,
      Instant createdAt,
      String campaignName,
      String campaignCode,
      String officeName,
      String plan,
      Instant freeEndsAt) {}
}
