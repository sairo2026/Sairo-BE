package com.sairo.be.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public abstract class VisitOutcomeTestBase extends VisitSelectionTestBase {

  public record TwoVisits(
      RequestFixture base, long firstItemId, long secondItemId, String firstToken) {}

  protected static LocalDate visitDay() {
    return today().plusDays(3);
  }

  protected static java.time.Instant at(LocalDate day, String time) {
    return ZonedDateTime.parse(day + "T" + time + ":00+09:00").toInstant();
  }

  protected static String minutesAgo(int minutes) {
    return OffsetDateTime.now(KST).minusMinutes(minutes).truncatedTo(ChronoUnit.SECONDS).toString();
  }

  protected static String coordinationUrl(long coordinationId) {
    return "/api/visit-coordinations/" + coordinationId;
  }

  protected static String itemUrl(long itemId) {
    return "/api/visit-schedule-items/" + itemId;
  }

  protected SelectionFixture confirmed() throws Exception {
    SelectionFixture s = selectionFixture();
    selectNow(s.token(), s.requestId()).andExpect(status().isOk());
    return s;
  }

  protected TwoVisits twoConfirmed() throws Exception {
    RequestFixture f = requestFixture();
    ExtraCandidate extra = addCandidateWithApproval(f);
    long first = submit(f);
    long second =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            requestUrl(f.coordinationId()),
                            requestBody(extra.candidateId(), visitDay(), "13:00", "13:30"),
                            f.cookie()))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("requestId")
            .longValue();
    String firstToken = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());
    String secondToken = issueApproverToken(f.cookie(), extra.propertyId(), extra.approvalId());
    selectNow(firstToken, first).andExpect(status().isOk());
    selectNow(secondToken, second).andExpect(status().isOk());
    return new TwoVisits(
        f,
        itemOfCandidate(f.coordinationId(), f.candidateId()),
        itemOfCandidate(f.coordinationId(), extra.candidateId()),
        firstToken);
  }

  protected long scheduledItem(long coordinationId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_schedule_item WHERE coordination_id = ? AND result ="
                + " 'SCHEDULED' ORDER BY id DESC LIMIT 1",
            Long.class,
            coordinationId);
    return id == null ? -1 : id;
  }

  protected long itemOfCandidate(long coordinationId, long candidateId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_schedule_item WHERE coordination_id = ? AND"
                + " candidate_property_id = ? ORDER BY id DESC LIMIT 1",
            Long.class,
            coordinationId,
            candidateId);
    return id == null ? -1 : id;
  }

  protected String itemResult(long itemId) {
    return jdbcTemplate.queryForObject(
        "SELECT result FROM visit_schedule_item WHERE id = ?", String.class, itemId);
  }

  protected ResultActions patchResult(Cookie cookie, long itemId, String body, Object version)
      throws Exception {
    return mockMvc.perform(jsonPatchAs(itemUrl(itemId) + "/result", body, cookie, version));
  }

  protected ResultActions recordResult(SelectionFixture s, long itemId, String body)
      throws Exception {
    return patchResult(s.cookie(), itemId, body, coordinationVersion(s.coordinationId()));
  }

  protected static String completedBody(int minutesAgo) {
    return "{\"result\":\"COMPLETED\",\"actualVisitedAt\":\"" + minutesAgo(minutesAgo) + "\"}";
  }

  protected ResultActions postVersioned(String url, String body, Cookie cookie, Object version)
      throws Exception {
    MockHttpServletRequestBuilder builder = jsonPostAs(url, body, cookie);
    if (version != null) {
      builder.header("If-Match", String.valueOf(version));
    }
    return mockMvc.perform(builder);
  }

  protected ResultActions cancel(Cookie cookie, long coordinationId, String body) throws Exception {
    return postVersioned(
        coordinationUrl(coordinationId) + "/cancel",
        body,
        cookie,
        coordinationVersion(coordinationId));
  }

  protected String reissueCustomerToken(Cookie cookie, long coordinationId) throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        coordinationUrl(coordinationId) + "/customer-link/reissue", "", cookie))
                .andExpect(status().isOk())
                .andReturn())
        .get("token")
        .asString();
  }

  protected long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? -1 : value;
  }
}
