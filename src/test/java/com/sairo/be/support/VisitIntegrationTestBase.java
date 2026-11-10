package com.sairo.be.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

public abstract class VisitIntegrationTestBase extends CustomerIntegrationTestBase {

  protected static final String NOTICE_VERSION = "1.0";
  protected static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private int propertySequence;

  @BeforeEach
  void seedVisitLegalRows() {
    propertySequence = 0;
    jdbcTemplate.update(
        "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required,"
            + " document_url, content_hash, effective_at, display_context, reconsent_required)"
            + " VALUES ('GUEST_LINK_NOTICE_CUSTOMER', ?, '고객 링크 개인정보 안내', 'NOTICE', false,"
            + " '/legal/link-notice/customer', ?, now() - interval '1 day', 'LINK_NOTICE', false)",
        NOTICE_VERSION,
        "b".repeat(64));
    jdbcTemplate.update(
        "INSERT INTO legal_retention_rule (rule_code, data_group_label, retention_days,"
            + " anchor_event, basis_note, status) VALUES ('GUEST_INPUT_AFTER_APPROVAL_END',"
            + " '비회원 링크 입력 데이터', 90, 'APPROVAL_END', '내부 기준', 'ASSUMPTION')");
  }

  protected static LocalDate today() {
    return LocalDate.now(KST);
  }

  protected long newProperty(Cookie cookie) throws Exception {
    propertySequence++;
    return createProperty(cookie, "후보 매물 " + propertySequence, categoryIdOf("APARTMENT"));
  }

  protected String createBody(
      long customerId, Long managerId, LocalDate start, LocalDate end, List<Long> propertyIds) {
    return "{\"customerId\":"
        + customerId
        + ",\"candidatePropertyIds\":["
        + propertyIds.stream().map(String::valueOf).collect(Collectors.joining(","))
        + "]"
        + (managerId == null ? "" : ",\"managerMembershipId\":" + managerId)
        + ",\"periodStartDate\":\""
        + start
        + "\",\"periodEndDate\":\""
        + end
        + "\"}";
  }

  protected JsonNode createCoordination(
      Cookie cookie, long customerId, Long managerId, LocalDate start, LocalDate end, Long... ids)
      throws Exception {
    return AuthFlowSupport.json(
        mockMvc
            .perform(
                jsonPostAs(
                    "/api/visit-coordinations",
                    createBody(customerId, managerId, start, end, List.of(ids)),
                    cookie))
            .andExpect(status().isCreated())
            .andReturn());
  }

  protected long coordinationVersion(long coordinationId) {
    Long version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM visit_coordination WHERE id = ?", Long.class, coordinationId);
    return version == null ? -1 : version;
  }

  protected String coordinationStatus(long coordinationId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM visit_coordination WHERE id = ?", String.class, coordinationId);
  }

  protected void setCoordinationStatus(long coordinationId, String status) {
    if ("CANCELLED".equals(status)) {
      jdbcTemplate.update(
          "UPDATE visit_coordination SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?",
          coordinationId);
      return;
    }
    jdbcTemplate.update(
        "UPDATE visit_coordination SET status = ?, cancelled_at = NULL WHERE id = ?",
        status,
        coordinationId);
  }

  protected void addAgentToRoster(long officeId) {
    jdbcTemplate.update(
        "INSERT INTO employment_roster (office_id, person_name, legal_qualification,"
            + " employment_start_date, confirmation_source) VALUES (?, '명부 공인중개사', 'AGENT',"
            + " current_date - 365, 'MANUAL_ENTRY')",
        officeId);
  }

  protected String issueToken(Cookie cookie, long coordinationId) throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/visit-coordinations/" + coordinationId + "/customer-link",
                        "",
                        cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("token")
        .asString();
  }
}
