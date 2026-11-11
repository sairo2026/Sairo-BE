package com.sairo.be.domain.support.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.support.HmacSupport;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.DeskIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class EnterpriseLeadIntegrationTest extends DeskIntegrationTestBase {

  private static final String LEAD_BODY =
      "{\"companyName\":\"(주)샘플부동산\",\"contactName\":\"홍길동\","
          + "\"contactEmail\":\"contact@example.com\",\"contactPhone\":\"010-0000-0000\","
          + "\"branchCount\":3,\"expectedSeats\":12,\"migrationNeeded\":true,"
          + "\"content\":\"지점 3곳 통합 도입을 검토 중입니다.\",\"privacyNoticeAcknowledged\":true}";

  private record Lead(long id, String key) {}

  private ResultActions createLead(String body) throws Exception {
    return mockMvc.perform(AuthFlowSupport.jsonPost("/api/enterprise-leads", body));
  }

  private ResultActions freshLead(String body) throws Exception {
    jdbcTemplate.update("DELETE FROM rate_limit_counter");
    return createLead(body);
  }

  private Lead newLead() throws Exception {
    JsonNode created =
        AuthFlowSupport.json(createLead(LEAD_BODY).andExpect(status().isCreated()).andReturn());
    return new Lead(created.get("leadId").longValue(), created.get("trackingKey").asString());
  }

  private ResultActions lookup(Lead lead, String key) throws Exception {
    var builder = get("/api/enterprise-leads/" + lead.id());
    if (key != null) {
      builder.header("X-Tracking-Key", key);
    }
    return mockMvc.perform(builder);
  }

  private ResultActions decide(long leadId, long quoteId, String action, String key, String body)
      throws Exception {
    var builder =
        AuthFlowSupport.jsonPost(
            "/api/enterprise-leads/" + leadId + "/quotes/" + quoteId + "/" + action,
            body == null ? "" : body);
    if (key != null) {
      builder.header("X-Tracking-Key", key);
    }
    return mockMvc.perform(builder);
  }

  private void assignedAndQuoted(Lead lead, long operatorId) {
    jdbcTemplate.update(
        "UPDATE enterprise_lead SET status = 'QUOTED', assigned_operator_id = ? WHERE id = ?",
        operatorId,
        lead.id());
  }

  private long insertQuote(
      long leadId, int version, long operatorId, int issuedDaysAgo, int expiresInDays) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO enterprise_quote (lead_id, version_no, office_count, seat_count, amount,"
                + " service_scope, tax_amount, delivery_due_at, payment_method, refund_terms,"
                + " expires_at, issued_at, issued_by_operator_id) VALUES (?, ?, 3, 12, 12000000,"
                + " '3개 지점 통합 도입', 1090909, current_date + 30, '계좌이체', '계약 조건에 따름',"
                + " now() + (? * interval '1 day'), now() - (? * interval '1 day'), ?)"
                + " RETURNING id",
            Long.class,
            leadId,
            version,
            expiresInDays,
            issuedDaysAgo,
            operatorId);
    jdbcTemplate.update(
        "UPDATE enterprise_lead SET quote_amount = 12000000,"
            + " quote_expires_at = now() + (? * interval '1 day') WHERE id = ?",
        expiresInDays,
        leadId);
    return id == null ? -1 : id;
  }

  private Lead quotedLead(int expiresInDays) throws Exception {
    Lead lead = newLead();
    long operatorId = insertOperator("김지원");
    assignedAndQuoted(lead, operatorId);
    quoteIds.put(lead.id(), insertQuote(lead.id(), 1, operatorId, 0, expiresInDays));
    return lead;
  }

  private final java.util.Map<Long, Long> quoteIds = new java.util.HashMap<>();

  @Test
  void 로그인_없이_접수하면_조회키를_한_번만_주고_해시만_저장한다() throws Exception {
    JsonNode created =
        AuthFlowSupport.json(
            createLead(LEAD_BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.leadId").isNumber())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn());

    String key = created.get("trackingKey").asString();
    assertThat(key).hasSize(43);
    String stored =
        jdbcTemplate.queryForObject("SELECT tracking_key_hash FROM enterprise_lead", String.class);
    assertThat(stored).isEqualTo(HmacSupport.sha256Hex(key)).isNotEqualTo(key);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM enterprise_lead WHERE tracking_key_hash = ?",
                Long.class,
                key))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT event_type || ':' || actor_type || ':' || to_status"
                    + " FROM enterprise_lead_status_history",
                String.class))
        .isEqualTo("RECEIVED:CUSTOMER:RECEIVED");
  }

  @Test
  void 접수_입력이_잘못되면_400이고_저장하지_않는다() throws Exception {
    assertFieldError(
        freshLead(LEAD_BODY.replace("\"companyName\":\"(주)샘플부동산\",", "")),
        "companyName",
        "REQUIRED");
    assertFieldError(
        freshLead(LEAD_BODY.replace("contact@example.com", "not-an-email")),
        "contactEmail",
        "INVALID_FORMAT");
    assertFieldError(
        freshLead(LEAD_BODY.replace("010-0000-0000", "전화번호")), "contactPhone", "INVALID_FORMAT");
    assertFieldError(
        freshLead(LEAD_BODY.replace("\"branchCount\":3", "\"branchCount\":0")),
        "branchCount",
        "OUT_OF_RANGE");
    assertFieldError(
        freshLead(LEAD_BODY.replace("\"expectedSeats\":12", "\"expectedSeats\":0")),
        "expectedSeats",
        "OUT_OF_RANGE");
    assertFieldError(
        freshLead(LEAD_BODY.replace("\"migrationNeeded\":true", "\"migrationNeeded\":\"예\"")),
        "migrationNeeded",
        "TYPE_MISMATCH");
    assertFieldError(
        freshLead(
            LEAD_BODY.replace(
                "\"privacyNoticeAcknowledged\":true", "\"privacyNoticeAcknowledged\":false")),
        "privacyNoticeAcknowledged",
        "INVALID_VALUE");
    assertFieldError(
        freshLead(LEAD_BODY.replace("\"content\"", "\"trackingKey\":\"x\",\"content\"")),
        "trackingKey",
        "NOT_ALLOWED");
    assertThat(count("enterprise_lead")).isZero();
  }

  @Test
  void 같은_IP는_한_시간에_5건까지만_접수하고_넘으면_429다() throws Exception {
    for (int i = 0; i < 5; i++) {
      createLead(LEAD_BODY).andExpect(status().isCreated());
    }

    assertError(createLead(LEAD_BODY), ErrorCode.RATE_LIMITED);
    assertThat(count("enterprise_lead")).isEqualTo(5);
  }

  @Test
  void 조회키로_진행_상태를_조회하고_문의_원문과_연락처는_돌려주지_않는다() throws Exception {
    Lead lead = newLead();

    String body =
        lookup(lead, lead.key())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.leadId").value(lead.id()))
            .andExpect(jsonPath("$.status").value("RECEIVED"))
            .andExpect(jsonPath("$.quoteId").doesNotExist())
            .andExpect(jsonPath("$.quoteVersion").doesNotExist())
            .andExpect(jsonPath("$.quoteAmount").doesNotExist())
            .andExpect(jsonPath("$.quoteExpiresAt").doesNotExist())
            .andExpect(jsonPath("$.version").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .doesNotContain("contact@example.com", "010-0000-0000", "지점 3곳", "샘플부동산", "홍길동");
  }

  @Test
  void 조회키가_없거나_틀리거나_다른_문의의_키면_404이다() throws Exception {
    Lead lead = newLead();
    Lead other = newLead();

    assertError(lookup(lead, null), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(lookup(lead, "틀린-조회키"), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(lookup(lead, other.key()), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(lookup(lead, "x".repeat(300)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(lookup(new Lead(99999, lead.key()), lead.key()), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(get("/api/enterprise-leads/abc").header("X-Tracking-Key", lead.key())),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 견적이_제시되면_최신_견적_정보를_보여_준다() throws Exception {
    Lead lead = quotedLead(14);
    long quoteId = quoteIds.get(lead.id());

    lookup(lead, lead.key())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUOTED"))
        .andExpect(jsonPath("$.quoteId").value(quoteId))
        .andExpect(jsonPath("$.quoteVersion").value(1))
        .andExpect(jsonPath("$.quoteAmount").value(12000000))
        .andExpect(jsonPath("$.quoteExpiresAt").exists());
  }

  @Test
  void 조회는_문의당_분당_20회로_제한한다() throws Exception {
    Lead lead = newLead();
    for (int i = 0; i < 20; i++) {
      lookup(lead, lead.key()).andExpect(status().isOk());
    }

    assertError(lookup(lead, lead.key()), ErrorCode.RATE_LIMITED);
  }

  @Test
  void 조회키로_견적을_수락하면_ACCEPTED가_되고_수락_시각을_한_번_기록한다() throws Exception {
    Lead lead = quotedLead(14);
    long quoteId = quoteIds.get(lead.id());

    decide(lead.id(), quoteId, "accept", lead.key(), "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.leadId").value(lead.id()))
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.quoteId").value(quoteId))
        .andExpect(jsonPath("$.quoteVersion").value(1))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.updatedAt").exists())
        .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.greaterThan(1)));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT accepted_at IS NOT NULL AND rejected_at IS NULL FROM enterprise_quote",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT event_type || ':' || actor_type || ':' || from_status || ':' || to_status"
                    + " FROM enterprise_lead_status_history WHERE quote_id IS NOT NULL",
                String.class))
        .isEqualTo("QUOTE_ACCEPTED:CUSTOMER:QUOTED:ACCEPTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT billing_invoice_id IS NULL FROM enterprise_quote", Boolean.class))
        .isTrue();

    assertError(
        decide(lead.id(), quoteId, "accept", lead.key(), null), ErrorCode.INVALID_TRANSITION);
    assertError(
        decide(lead.id(), quoteId, "reject", lead.key(), null), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 조회키로_견적을_거절하면_REJECTED가_되고_이후_수락할_수_없다() throws Exception {
    Lead lead = quotedLead(14);
    long quoteId = quoteIds.get(lead.id());

    decide(lead.id(), quoteId, "reject", lead.key(), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.quoteId").value(quoteId));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT rejected_at IS NOT NULL AND accepted_at IS NULL FROM enterprise_quote",
                Boolean.class))
        .isTrue();
    assertError(
        decide(lead.id(), quoteId, "accept", lead.key(), null), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 유효기한이_지난_견적은_409_QUOTE_EXPIRED다() throws Exception {
    Lead lead = newLead();
    long operatorId = insertOperator("김지원");
    assignedAndQuoted(lead, operatorId);
    long quoteId = insertQuote(lead.id(), 1, operatorId, 20, -1);

    assertError(decide(lead.id(), quoteId, "accept", lead.key(), null), ErrorCode.QUOTE_EXPIRED);
    assertError(decide(lead.id(), quoteId, "reject", lead.key(), null), ErrorCode.QUOTE_EXPIRED);
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM enterprise_lead", String.class))
        .isEqualTo("QUOTED");
  }

  @Test
  void 새_버전으로_대체된_견적은_409_QUOTE_SUPERSEDED다() throws Exception {
    Lead lead = newLead();
    long operatorId = insertOperator("김지원");
    assignedAndQuoted(lead, operatorId);
    long first = insertQuote(lead.id(), 1, operatorId, 3, 11);
    jdbcTemplate.update("UPDATE enterprise_quote SET superseded_at = now() WHERE id = ?", first);
    long second = insertQuote(lead.id(), 2, operatorId, 0, 14);

    assertError(decide(lead.id(), first, "accept", lead.key(), null), ErrorCode.QUOTE_SUPERSEDED);
    assertError(decide(lead.id(), first, "reject", lead.key(), null), ErrorCode.QUOTE_SUPERSEDED);
    decide(lead.id(), second, "accept", lead.key(), null).andExpect(status().isOk());
  }

  @Test
  void 최신_버전이_아닌_견적은_대체_표시가_없어도_QUOTE_SUPERSEDED다() throws Exception {
    Lead lead = newLead();
    long operatorId = insertOperator("김지원");
    assignedAndQuoted(lead, operatorId);
    long first = insertQuote(lead.id(), 1, operatorId, 3, 11);
    insertQuote(lead.id(), 2, operatorId, 0, 14);

    assertError(decide(lead.id(), first, "accept", lead.key(), null), ErrorCode.QUOTE_SUPERSEDED);
  }

  @Test
  void 견적이_제시된_상태가_아니면_409_INVALID_TRANSITION이다() throws Exception {
    Lead lead = newLead();
    long operatorId = insertOperator("김지원");
    jdbcTemplate.update(
        "UPDATE enterprise_lead SET status = 'CONSULTING', assigned_operator_id = ? WHERE id = ?",
        operatorId,
        lead.id());
    long quoteId = insertQuote(lead.id(), 1, operatorId, 0, 14);

    assertError(
        decide(lead.id(), quoteId, "accept", lead.key(), null), ErrorCode.INVALID_TRANSITION);
    assertError(
        decide(lead.id(), quoteId, "reject", lead.key(), null), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 조회키가_틀리거나_다른_문의의_견적이거나_없는_견적이면_404이다() throws Exception {
    Lead lead = quotedLead(14);
    Lead other = quotedLead(14);
    long quoteId = quoteIds.get(lead.id());
    long otherQuote = quoteIds.get(other.id());

    assertError(decide(lead.id(), quoteId, "accept", null, null), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        decide(lead.id(), quoteId, "accept", other.key(), null), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        decide(lead.id(), otherQuote, "accept", lead.key(), null), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(decide(lead.id(), 99999, "reject", lead.key(), null), ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM enterprise_quote WHERE accepted_at IS NOT NULL OR rejected_at IS NOT NULL",
                Long.class))
        .isZero();
  }

  @Test
  void 수락과_거절은_본문의_허용되지_않는_필드와_숫자가_아닌_경로를_400으로_거절한다() throws Exception {
    Lead lead = quotedLead(14);
    long quoteId = quoteIds.get(lead.id());

    assertFieldError(
        decide(lead.id(), quoteId, "accept", lead.key(), "{\"status\":\"ACCEPTED\"}"),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        decide(lead.id(), quoteId, "reject", lead.key(), "{\"reason\":\"비쌈\"}"),
        "reason",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(
            AuthFlowSupport.jsonPost(
                    "/api/enterprise-leads/" + lead.id() + "/quotes/abc/accept", "{}")
                .header("X-Tracking-Key", lead.key())),
        ErrorCode.INVALID_REQUEST);
  }
}
