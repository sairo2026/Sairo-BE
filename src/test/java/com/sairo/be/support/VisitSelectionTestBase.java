package com.sairo.be.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

public abstract class VisitSelectionTestBase extends VisitRequestIntegrationTestBase {

  public record SelectionFixture(RequestFixture base, long requestId, String token) {

    public long coordinationId() {
      return base.coordinationId();
    }

    public long candidateId() {
      return base.candidateId();
    }

    public Cookie cookie() {
      return base.cookie();
    }
  }

  public record SecondRequest(long coordinationId, long candidateId, long requestId) {}

  protected SelectionFixture selectionFixture() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());
    return new SelectionFixture(f, requestId, token);
  }

  protected SecondRequest secondRequest(
      RequestFixture f, Cookie applicant, String from, String to, int hourFrom, int hourTo)
      throws Exception {
    long customer =
        insertCustomer(f.owner().office().getId(), f.owner().membership().getId(), "김다른");
    long coordinationId =
        createCoordination(
                f.cookie(), customer, null, today(), today().plusDays(10), f.propertyId())
            .get("coordinationId")
            .longValue();
    long candidateId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            coordinationId);
    submitCustomerSlots(f.cookie(), coordinationId, today().plusDays(3), hourFrom, hourTo);
    setCoordinationStatus(coordinationId, "FINAL_CONFIRMATION_REQUIRED");
    long requestId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            requestUrl(coordinationId),
                            requestBody(candidateId, today().plusDays(3), from, to),
                            applicant))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("requestId")
            .longValue();
    return new SecondRequest(coordinationId, candidateId, requestId);
  }

  protected static String compareUrl(String token) {
    return "/api/public/approver-property-links/" + token + "/visit-requests";
  }

  protected ResultActions compare(String token) throws Exception {
    return mockMvc.perform(get(compareUrl(token)));
  }

  protected String selectBody(long... ids) {
    return selectBody(NOTICE_VERSION, ids);
  }

  protected String selectBody(String noticeVersion, long... ids) {
    return "{\"comparedRequestIds\":["
        + LongStream.of(ids).mapToObj(String::valueOf).collect(Collectors.joining(","))
        + "],\"selectionNote\":\"오전이 좋습니다\",\"acknowledgedNoticeVersion\":\""
        + noticeVersion
        + "\"}";
  }

  protected ResultActions select(String token, long requestId, Object version, String body)
      throws Exception {
    return select(token, requestId, version, body, null);
  }

  protected ResultActions select(
      String token, long requestId, Object version, String body, String idempotencyKey)
      throws Exception {
    MockHttpServletRequestBuilder builder =
        post(compareUrl(token) + "/" + requestId + "/select")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (version != null) {
      builder.header("If-Match", String.valueOf(version));
    }
    if (idempotencyKey != null) {
      builder.header("Idempotency-Key", idempotencyKey);
    }
    return mockMvc.perform(AuthFlowSupport.withCsrf(builder));
  }

  protected ResultActions selectNow(String token, long requestId) throws Exception {
    return select(token, requestId, requestVersion(requestId), selectBody(requestId));
  }

  protected long requestVersion(long requestId) {
    Long version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM visit_request WHERE id = ?", Long.class, requestId);
    return version == null ? -1 : version;
  }

  protected long insertBusyItem(
      long coordinationId,
      long candidateId,
      long officeId,
      LocalDate day,
      String from,
      String to,
      String travelEnd) {
    String start = day + "T" + from + ":00+09:00";
    String end = day + "T" + to + ":00+09:00";
    String travel = travelEnd == null ? null : day + "T" + travelEnd + ":00+09:00";
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_schedule_item (coordination_id, office_id, candidate_property_id,"
                + " scheduled_start_at, scheduled_end_at, sort_order, result, travel_starts_at,"
                + " travel_ends_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, 1,"
                + " 'SCHEDULED', CASE WHEN ?::text IS NULL THEN NULL ELSE ?::timestamptz END,"
                + " CASE WHEN ?::text IS NULL THEN NULL ELSE ?::timestamptz END) RETURNING id",
            Long.class,
            coordinationId,
            officeId,
            candidateId,
            start,
            end,
            travel == null ? null : end,
            travel == null ? null : end,
            travel,
            travel);
    return id == null ? -1 : id;
  }

  protected long scheduleItemCount(long coordinationId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM visit_schedule_item WHERE coordination_id = ?",
            Long.class,
            coordinationId);
    return count == null ? -1 : count;
  }
}
