package com.sairo.be.domain.ops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.billing.service.BillingPeriods;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class OpsPromotionIntegrationTest extends OpsConsoleTestBase {
  private static final String ROOT = "/api/ops/promotions";
  private BillingFixtures fixtures;
  private Operator creator;
  private Cookie creatorSession;
  private Operator approver;
  private Cookie approverSession;

  @BeforeEach
  void setup() {
    fixtures = new BillingFixtures(jdbcTemplate);
    fixtures.standardPrice("2026-PROMO", Instant.now().minus(400, ChronoUnit.DAYS));
    creator = operator("프로모션 작성", OperatorRole.BILLING);
    creatorSession = sessionOf(creator);
    stepUp(creatorSession);
    approver = operator("프로모션 승인", OperatorRole.BILLING);
    approverSession = sessionOf(approver);
    stepUp(approverSession);
  }

  @Test
  void 승인된_셀프_코드는_사무소_대표만_한번_적용하고_회원_행위자를_남긴다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    long officeId = owner.office().getId();
    fixtures.subscription(officeId).prePaymentSince(Instant.now()).insert();
    var member = staff(OfficeMembershipRole.AGENT);
    fixtures.subscription(member.office().getId()).prePaymentSince(Instant.now()).insert();
    var anotherOwner = staff(OfficeMembershipRole.OWNER);
    fixtures.subscription(anotherOwner.office().getId()).prePaymentSince(Instant.now()).insert();
    Instant now = Instant.now();
    String code = "LAUNCH2026OWNER01";
    String body =
        "{\"name\":\"셀프 적용\",\"code\":\""
            + code
            + "\",\"applicationStartsAt\":\""
            + kst(now.minusSeconds(60))
            + "\",\"applicationEndsAt\":\""
            + kst(now.plusSeconds(86_400))
            + "\",\"freeMonths\":2,\"maxOffices\":1,\"selfRedeemable\":true}";
    String response =
        post(ROOT + "/campaigns", body, creatorSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long campaignId = Long.parseLong(response.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
    String duplicateResponse =
        post(ROOT + "/campaigns", body, creatorSession)
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(duplicateResponse).doesNotContain(code);
    String redeem = "{\"code\":\"" + code + "\"}";

    post("/api/billing/promotion-periods/redeem", redeem, memberSession(member))
        .andExpect(status().isForbidden());
    post("/api/billing/promotion-periods/redeem", redeem, memberSession(owner))
        .andExpect(status().isConflict());
    post(ROOT + "/campaigns/" + campaignId + "/activate", "{}", approverSession)
        .andExpect(status().isOk());
    mockMvc
        .perform(get(ROOT + "/campaigns").cookie(creatorSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].selfRedeemable").value(true))
        .andExpect(jsonPath("$[0].code").value("****ER01"));
    post("/api/billing/promotion-periods/redeem", redeem, memberSession(owner))
        .andExpect(status().isCreated());
    mockMvc
        .perform(get("/api/billing/promotion-periods/current").cookie(memberSession(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.campaignCode").value("****ER01"));
    mockMvc
        .perform(get("/api/billing/promotion-periods").cookie(memberSession(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].campaignCode").value("****ER01"));
    post("/api/billing/promotion-periods/redeem", redeem, memberSession(anotherOwner))
        .andExpect(status().isConflict());
    post("/api/billing/promotion-periods/redeem", redeem, memberSession(owner))
        .andExpect(status().isConflict());

    assertThat(count("SELECT count(*) FROM promotion_grant WHERE office_id = ?", officeId))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT redeemed_by_membership_id FROM promotion_grant WHERE office_id = ?",
                Long.class,
                officeId))
        .isEqualTo(owner.membership().getId());
    assertThat(
            count(
                "SELECT count(*) FROM promotion_grant WHERE office_id = ? AND granted_by_operator_id IS NULL",
                officeId))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(count("SELECT count(*) FROM ops_audit_log WHERE detail LIKE ?", "%" + code + "%"))
        .isZero();

    long grantId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM promotion_grant WHERE office_id = ?", Long.class, officeId);
    post(ROOT + "/grants/" + grantId + "/revoke", "{}", creatorSession).andExpect(status().isOk());
    String nextCode = "LAUNCH2026OWNER02";
    String secondCampaign = body.replace(code, nextCode);
    String secondResponse =
        post(ROOT + "/campaigns", secondCampaign, creatorSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long secondCampaignId =
        Long.parseLong(secondResponse.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
    post(ROOT + "/campaigns/" + secondCampaignId + "/activate", "{}", approverSession)
        .andExpect(status().isOk());
    post(
            "/api/billing/promotion-periods/redeem",
            "{\"code\":\"" + nextCode + "\"}",
            memberSession(owner))
        .andExpect(status().isConflict());
  }

  @Test
  void 셀프_코드_추측은_사무소별_요청_제한에_걸린다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    String invalid = "{\"code\":\"INVALIDPROMO2026\"}";
    for (int attempt = 0; attempt < 5; attempt++) {
      post("/api/billing/promotion-periods/redeem", invalid, memberSession(owner))
          .andExpect(status().isConflict());
    }
    post("/api/billing/promotion-periods/redeem", invalid, memberSession(owner))
        .andExpect(status().isTooManyRequests());
    assertThat(
            count(
                "SELECT count(*) FROM promotion_grant WHERE office_id = ?", owner.office().getId()))
        .isZero();
  }

  @Test
  void 운영자_2인_승인_뒤_사무소에_0원_구독을_지급하고_동일_사무소_중복_지급을_막는다() throws Exception {
    var owner = staff(OfficeMembershipRole.OWNER);
    long officeId = owner.office().getId();
    long subscriptionId = fixtures.subscription(officeId).prePaymentSince(Instant.now()).insert();
    Instant now = Instant.now();
    String body =
        "{\"name\":\"출시 무료\",\"code\":\"LAUNCH-TEST-2026A\","
            + "\"applicationStartsAt\":\""
            + kst(now.minusSeconds(60))
            + "\","
            + "\"applicationEndsAt\":\""
            + kst(now.plusSeconds(86_400))
            + "\","
            + "\"freeMonths\":2,\"maxOffices\":1}";
    String response =
        post(ROOT + "/campaigns", body, creatorSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long campaignId = Long.parseLong(response.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));

    post(ROOT + "/campaigns/" + campaignId + "/activate", "{}", creatorSession)
        .andExpect(status().isConflict());
    post(ROOT + "/campaigns/" + campaignId + "/activate", "{}", approverSession)
        .andExpect(status().isOk());
    post(
            "/api/billing/promotion-periods/redeem",
            "{\"code\":\"LAUNCH-TEST-2026A\"}",
            memberSession(owner))
        .andExpect(status().isConflict());
    String grantBody = "{\"officeId\":" + officeId + "}";
    post(ROOT + "/campaigns/" + campaignId + "/grants", grantBody, creatorSession)
        .andExpect(status().isCreated());
    post(ROOT + "/campaigns/" + campaignId + "/grants", grantBody, creatorSession)
        .andExpect(status().isConflict());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId))
        .isEqualTo("TRIAL");
    assertThat(count("SELECT count(*) FROM promotion_period WHERE office_id = ?", officeId))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM billing_invoice")).isZero();
    assertThat(auditCount("PROMOTION_GRANTED")).isEqualTo(1);

    mockMvc
        .perform(get("/api/billing/promotion-periods").cookie(memberSession(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].chargedAmount").value(0));
    var other = staff(OfficeMembershipRole.OWNER);
    fixtures.subscription(other.office().getId()).prePaymentSince(Instant.now()).insert();
    mockMvc
        .perform(get("/api/billing/promotion-periods").cookie(memberSession(other)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    long grantId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM promotion_grant WHERE office_id = ?", Long.class, officeId);
    Instant historicalAnchor = now.minus(40, ChronoUnit.DAYS);
    Instant missedStart = BillingPeriods.boundary(historicalAnchor, 1);
    jdbcTemplate.update(
        "UPDATE promotion_grant SET status = 'RECONCILIATION_REQUIRED', starts_at = ?,"
            + " billing_anchor_at = ?, next_period_start = ? WHERE id = ?",
        java.sql.Timestamp.from(historicalAnchor),
        java.sql.Timestamp.from(historicalAnchor),
        java.sql.Timestamp.from(missedStart),
        grantId);
    post(
            ROOT + "/grants/" + grantId + "/reconcile-period",
            "{\"historicalMembers\":1,\"reason\":\"verified historical membership log\"}",
            creatorSession)
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, grantId))
        .isEqualTo("ACTIVE");
    assertThat(
            count(
                "SELECT count(*) FROM promotion_period WHERE grant_id = ?"
                    + " AND record_source = 'RECONCILED'",
                grantId))
        .isEqualTo(1);
    assertThat(auditCount("PROMOTION_PERIOD_RECONCILED")).isEqualTo(1);

    jdbcTemplate.update(
        "UPDATE promotion_grant SET status = 'RECONCILIATION_REQUIRED' WHERE id = ?", grantId);
    jdbcTemplate.update(
        "UPDATE subscription SET status = 'SUSPENDED', suspended_at = now() WHERE id = ?",
        subscriptionId);
    post(
            ROOT + "/grants/" + grantId + "/reconcile-period",
            "{\"historicalMembers\":1,\"reason\":\"verified historical membership log\"}",
            creatorSession)
        .andExpect(status().isConflict());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId))
        .isEqualTo("SUSPENDED");
    jdbcTemplate.update(
        "UPDATE subscription SET status = 'TRIAL', suspended_at = NULL WHERE id = ?",
        subscriptionId);
    jdbcTemplate.update(
        "INSERT INTO subscription_cancellation (subscription_id, effective_at, cancelled_by_membership_id)"
            + " VALUES (?, ?, ?)",
        subscriptionId,
        java.sql.Timestamp.from(now.minusSeconds(60)),
        owner.membership().getId());
    post(
            ROOT + "/grants/" + grantId + "/reconcile-period",
            "{\"historicalMembers\":1,\"reason\":\"verified pending cancellation\"}",
            creatorSession)
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM promotion_grant WHERE id = ?", String.class, grantId))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM subscription WHERE id = ?", String.class, subscriptionId))
        .isEqualTo("ENDED");
    assertThat(auditCount("PROMOTION_RECONCILIATION_CANCELLED")).isEqualTo(1);
  }

  @Test
  void 재인증하지_않은_운영자는_프로모션_지급을_승인할_수_없다() throws Exception {
    String body =
        "{\"name\":\"재인증 확인\",\"code\":\"STEPUP-TEST\","
            + "\"applicationStartsAt\":\""
            + kst(Instant.now().minusSeconds(60))
            + "\","
            + "\"applicationEndsAt\":\""
            + kst(Instant.now().plusSeconds(86_400))
            + "\","
            + "\"freeMonths\":1}";
    String response =
        post(ROOT + "/campaigns", body, creatorSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long campaignId = Long.parseLong(response.replaceAll("(?s).*\"id\":(\\d+).*", "$1"));
    expireStepUp(approverSession);
    post(ROOT + "/campaigns/" + campaignId + "/activate", "{}", approverSession)
        .andExpect(status().isUnauthorized());
    assertThat(auditCount("PROMOTION_CAMPAIGN_ACTIVATED")).isZero();
  }

  private static String kst(Instant at) {
    return OffsetDateTime.ofInstant(at, ZoneOffset.ofHours(9)).toString();
  }
}
