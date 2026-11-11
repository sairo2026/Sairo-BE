package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OpsEnterpriseLeadIntegrationTest extends OpsConsoleTestBase {

  private static final String BASE = "/api/ops/enterprise-leads";
  private static final String QUOTE =
      "{\"officeCount\":3,\"seatCount\":12,\"quoteAmount\":12000000,\"serviceScope\":\"3개 지점"
          + " 통합\",\"taxAmount\":1090909,\"deliveryDueAt\":\"2027-01-10\",\"paymentMethod\":"
          + "\"계좌이체\",\"refundTerms\":\"계약서 별도 조항\"}";

  private Operator support;
  private Cookie supportSession;
  private Operator other;
  private Cookie otherSession;

  @BeforeEach
  void setUp() {
    support = operator("김지원", OperatorRole.SUPPORT);
    supportSession = sessionOf(support);
    other = operator("박상담", OperatorRole.SUPPORT);
    otherSession = sessionOf(other);
  }

  private long lead() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO enterprise_lead (company_name, contact_name, contact_phone, contact_email,"
            + " branch_count, expected_seats, migration_needed, content, tracking_key_hash, status)"
            + " VALUES ('(주)샘플부동산', '홍길동', '010-0000-0000', 'contact@example.com', 3, 12,"
            + " true, '지점 3곳 도입 검토', md5(random()::text) || md5(random()::text),"
            + " 'RECEIVED') RETURNING id",
        Long.class);
  }

  private long version(long leadId) {
    return count("SELECT version FROM enterprise_lead WHERE id = ?", leadId);
  }

  private String leadStatus(long leadId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM enterprise_lead WHERE id = ?", String.class, leadId);
  }

  private ResultActions act(long leadId, String action, String body, Cookie session)
      throws Exception {
    return mockMvc.perform(
        jsonPost(BASE + "/" + leadId + "/" + action, body, session)
            .header("If-Match", String.valueOf(version(leadId))));
  }

  private long consulting() throws Exception {
    long id = lead();
    act(id, "assign", "{\"assignedOperatorId\":" + support.id() + "}", supportSession)
        .andExpect(status().isOk());
    act(id, "consultation-start", "", supportSession).andExpect(status().isOk());
    return id;
  }

  private long quoteId(String json) {
    return Long.parseLong(json.replaceAll("(?s).*\"quoteId\":(\\d+).*", "$1"));
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_고객지원_역할이_없으면_403이다() throws Exception {
    Cookie billing = sessionOf(operator("이결제", OperatorRole.BILLING));
    for (String path : new String[] {BASE, BASE + "/1"}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(billing)), ErrorCode.FORBIDDEN);
    }
    for (String action :
        new String[] {"assign", "consultation-start", "quote", "contract", "close", "reopen"}) {
      assertError(post(BASE + "/1/" + action, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(BASE + "/1/" + action, "{}", billing), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 목록과_상세는_필터와_가능한_처리를_보이고_상세_조회를_감사_로그에_남긴다() throws Exception {
    long received = lead();
    long assigned = lead();
    act(assigned, "assign", "{\"assignedOperatorId\":" + support.id() + "}", supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.assignedOperatorName").value("김지원"));

    mockMvc
        .perform(get(BASE).cookie(supportSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mockMvc
        .perform(get(BASE).param("unassigned", "true").cookie(supportSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].leadId").value(received));
    mockMvc
        .perform(get(BASE).param("status", "ASSIGNED").cookie(supportSession))
        .andExpect(jsonPath("$.items[0].assignedOperatorName").value("김지원"));
    assertFieldError(
        mockMvc.perform(get(BASE).param("status", "NOPE").cookie(supportSession)),
        "status",
        "NOT_ALLOWED");

    mockMvc
        .perform(get(BASE + "/" + assigned).cookie(supportSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contactEmail").value("contact@example.com"))
        .andExpect(jsonPath("$.history[0].eventType").value("ASSIGNED"))
        .andExpect(jsonPath("$.history[0].assignedOperatorName").value("김지원"))
        .andExpect(
            jsonPath(
                "$.allowedActions", Matchers.contains("ASSIGN", "START_CONSULTATION", "CLOSE")));
    mockMvc
        .perform(get(BASE + "/" + assigned).cookie(otherSession))
        .andExpect(jsonPath("$.allowedActions", Matchers.contains("ASSIGN")));
    assertThat(auditCount("ENTERPRISE_LEAD_VIEWED")).isEqualTo(2);
    assertError(
        mockMvc.perform(get(BASE + "/999999").cookie(supportSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 배정과_상담_시작은_If_Match와_담당자_규칙을_지킨다() throws Exception {
    long id = lead();
    String body = "{\"assignedOperatorId\":" + support.id() + "}";
    assertFieldError(
        mockMvc.perform(jsonPost(BASE + "/" + id + "/assign", body, supportSession)),
        "If-Match",
        "REQUIRED");
    assertError(
        mockMvc.perform(
            jsonPost(BASE + "/" + id + "/assign", body, supportSession).header("If-Match", "9")),
        ErrorCode.VERSION_CONFLICT);
    Operator billing = operator("이결제", OperatorRole.BILLING);
    assertError(
        act(id, "assign", "{\"assignedOperatorId\":" + billing.id() + "}", supportSession),
        ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    assertError(
        mockMvc.perform(
            jsonPost(BASE + "/999999/assign", body, supportSession).header("If-Match", "1")),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(act(id, "consultation-start", "", supportSession), ErrorCode.INVALID_TRANSITION);

    act(id, "assign", body, supportSession).andExpect(status().isOk());
    assertError(act(id, "consultation-start", "", otherSession), ErrorCode.FORBIDDEN);
    act(id, "assign", "{\"assignedOperatorId\":" + other.id() + "}", supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"));
    act(id, "consultation-start", "", otherSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONSULTING"));
    assertThat(
            count(
                "SELECT count(*) FROM enterprise_lead_status_history WHERE lead_id = ? AND"
                    + " event_type IN ('ASSIGNED','REASSIGNED','CONSULTATION_STARTED')",
                id))
        .isEqualTo(3);
  }

  @Test
  void 견적은_담당자만_발행하고_새_버전이_이전_버전을_대체한다() throws Exception {
    long id = consulting();
    assertError(act(id, "quote", QUOTE, otherSession), ErrorCode.FORBIDDEN);
    assertError(
        act(id, "quote", QUOTE.replace("\"seatCount\":12", "\"seatCount\":0"), supportSession),
        ErrorCode.INVALID_REQUEST);

    String first =
        act(id, "quote", QUOTE, supportSession)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.quoteVersion").value(1))
            .andExpect(jsonPath("$.leadStatus").value("QUOTED"))
            .andExpect(jsonPath("$.supersededQuoteId").value(Matchers.nullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long firstId = quoteId(first);
    assertThat(
            count(
                "SELECT count(*) FROM enterprise_quote WHERE id = ? AND expires_at = issued_at +"
                    + " interval '14 days'",
                firstId))
        .isEqualTo(1);
    act(id, "quote", QUOTE.replace("12000000", "11000000"), supportSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.quoteVersion").value(2))
        .andExpect(jsonPath("$.supersededQuoteId").value(firstId));
    assertThat(
            count(
                "SELECT count(*) FROM enterprise_quote WHERE id = ? AND superseded_at IS NOT NULL",
                firstId))
        .isEqualTo(1);
    assertThat(count("SELECT quote_amount FROM enterprise_lead WHERE id = ?", id))
        .isEqualTo(11000000);
    mockMvc
        .perform(get(BASE + "/" + id).cookie(supportSession))
        .andExpect(jsonPath("$.quotes.length()").value(2))
        .andExpect(jsonPath("$.quotes[0].supersededAt").exists())
        .andExpect(jsonPath("$.quotes[1].issuedByOperatorName").value("김지원"))
        .andExpect(jsonPath("$.quotes[1].expired").value(false));
    assertThat(auditCount("ENTERPRISE_QUOTE_ISSUED")).isEqualTo(2);
  }

  @Test
  void 계약은_고객이_수락한_최신_견적이_있을_때만_기록한다() throws Exception {
    long id = consulting();
    String quote =
        act(id, "quote", QUOTE, supportSession).andReturn().getResponse().getContentAsString();
    String contract =
        "{\"contractReference\":\"C-2026-0042\",\"contractedAt\":\"2026-01-01T09:00:00+09:00\"}";
    assertError(act(id, "contract", contract, supportSession), ErrorCode.INVALID_TRANSITION);

    jdbcTemplate.update(
        "UPDATE enterprise_quote SET accepted_at = now() WHERE id = ?", quoteId(quote));
    jdbcTemplate.update("UPDATE enterprise_lead SET status = 'ACCEPTED' WHERE id = ?", id);
    assertError(act(id, "contract", contract, otherSession), ErrorCode.FORBIDDEN);
    assertFieldError(
        act(id, "contract", contract.replace("2026-01-01", "2999-01-01"), supportSession),
        "contractedAt",
        "OUT_OF_RANGE");
    act(id, "contract", contract, supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONTRACTED"))
        .andExpect(jsonPath("$.contractReference").value("C-2026-0042"));
    assertError(
        act(id, "close", "{\"closedReason\":\"NO_RESPONSE\"}", supportSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        act(id, "reopen", "{\"reason\":\"재문의\"}", supportSession), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 종료와_재개는_직전_단계로_돌아가고_견적_단계면_상담으로_돌아간다() throws Exception {
    long received = lead();
    assertFieldError(
        act(received, "close", "{\"closedReason\":\"OTHER\"}", otherSession),
        "closedNote",
        "REQUIRED");
    act(received, "close", "{\"closedReason\":\"DUPLICATE\"}", otherSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closedFromStatus").value("RECEIVED"));
    act(received, "reopen", "{\"reason\":\"고객 재문의\"}", supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECEIVED"))
        .andExpect(jsonPath("$.assignedOperatorId").value(Matchers.nullValue()));

    long quoted = consulting();
    act(quoted, "quote", QUOTE, supportSession).andExpect(status().isCreated());
    assertError(
        act(quoted, "close", "{\"closedReason\":\"NO_RESPONSE\"}", otherSession),
        ErrorCode.FORBIDDEN);
    act(
            quoted,
            "close",
            "{\"closedReason\":\"NO_RESPONSE\",\"closedNote\":\"14일 회신 없음\"}",
            supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.closedFromStatus").value("QUOTED"));
    jdbcTemplate.update(
        "UPDATE operator_account SET status = 'DISABLED' WHERE id = ?", support.id());
    assertError(
        act(quoted, "reopen", "{\"reason\":\"재문의\"}", otherSession), ErrorCode.ASSIGNEE_REQUIRED);
    assertError(
        act(
            quoted,
            "reopen",
            "{\"reason\":\"재문의\",\"assignedOperatorId\":" + support.id() + "}",
            otherSession),
        ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    act(
            quoted,
            "reopen",
            "{\"reason\":\"재문의\",\"assignedOperatorId\":" + other.id() + "}",
            otherSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONSULTING"))
        .andExpect(jsonPath("$.assignedOperatorId").value(other.id()));
    assertThat(leadStatus(quoted)).isEqualTo("CONSULTING");
    assertThat(
            count(
                "SELECT count(*) FROM enterprise_lead WHERE id = ? AND closed_reason IS NULL AND"
                    + " closed_at IS NULL",
                quoted))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM enterprise_lead_status_history WHERE lead_id = ? AND"
                    + " event_type IN ('CLOSED','REOPENED','REASSIGNED')",
                quoted))
        .isEqualTo(3);
  }
}
