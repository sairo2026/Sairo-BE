package com.sairo.be.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import tools.jackson.databind.JsonNode;

public abstract class ApproverIntegrationTestBase extends VisitIntegrationTestBase {

  protected static String approvalsUrl(long propertyId) {
    return "/api/properties/" + propertyId + "/access-approvals";
  }

  protected static String conditionsUrl(String token) {
    return "/api/public/approver-property-links/" + token + "/conditions";
  }

  protected String approvalBody(String role, LocalDate from, LocalDate until) {
    return "{\"approverType\":\"OCCUPANT\",\"approverName\":\"김현소\","
        + "\"approverPhone\":\"010-1111-2222\",\"validFrom\":\""
        + from
        + "T09:00:00+09:00\",\"validUntil\":\""
        + until
        + "T18:00:00+09:00\",\"source\":\"DIRECT\""
        + (role == null ? "" : ",\"approvalRole\":\"" + role + "\"")
        + "}";
  }

  protected long registerApproval(
      Cookie cookie, long propertyId, String role, int fromDays, int untilDays) throws Exception {
    JsonNode created =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        approvalsUrl(propertyId),
                        approvalBody(role, today().plusDays(fromDays), today().plusDays(untilDays)),
                        cookie))
                .andExpect(status().isCreated())
                .andReturn());
    return created.get("approvalId").longValue();
  }

  protected String issueApproverToken(Cookie cookie, long propertyId, long approvalId)
      throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        approvalsUrl(propertyId) + "/" + approvalId + "/approver-property-link",
                        "",
                        cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("token")
        .asString();
  }

  protected static String weekdayWindows() {
    return IntStream.rangeClosed(1, 7)
        .mapToObj(
            day ->
                "{\"windowType\":\"WEEKDAY\",\"weekday\":"
                    + day
                    + ",\"windowDate\":null,\"startsTime\":\"09:00\",\"endsTime\":\"18:00\","
                    + "\"isException\":false}")
        .collect(Collectors.joining(",", "[", "]"));
  }

  protected String conditionJson(boolean proxy, Map<String, String> overrides) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("appliesFrom", "\"" + today().minusDays(1) + "T00:00:00+09:00\"");
    fields.put("appliesUntil", "\"" + today().plusDays(60) + "T23:59:59+09:00\"");
    fields.put("maxVisitsPerDay", "3");
    fields.put("minGapMinutes", "30");
    fields.put("windows", weekdayWindows());
    fields.put("impactAcknowledged", "false");
    if (!proxy) {
      fields.put("acknowledgedNoticeVersion", "\"" + NOTICE_VERSION + "\"");
    }
    overrides.forEach(
        (key, value) -> {
          if (value == null) {
            fields.remove(key);
          } else {
            fields.put(key, value);
          }
        });
    return fields.entrySet().stream()
        .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
        .collect(Collectors.joining(",", "{", "}"));
  }

  protected String conditionJson(boolean proxy) {
    return conditionJson(proxy, Map.of());
  }

  protected long activeConditionId(long approvalId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_approver_condition WHERE approval_id = ? AND status = 'ACTIVE'",
            Long.class,
            approvalId);
    return id == null ? -1 : id;
  }

  protected long insertConfirmedVisit(
      TestStaffRef staff,
      long coordinationId,
      long propertyId,
      long approvalId,
      LocalDate day,
      int startHour,
      int endHour) {
    Long candidateId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ? AND property_id = ?",
            Long.class,
            coordinationId,
            propertyId);
    String start = day + "T" + String.format("%02d", startHour) + ":00:00+09:00";
    String end = day + "T" + String.format("%02d", endHour) + ":00:00+09:00";
    Long requestId =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_request (condition_id, approval_id, property_id, applicant_office_id,"
                + " applicant_membership_id, coordination_id, candidate_property_id,"
                + " requested_start_at, requested_end_at, expected_duration_minutes, status,"
                + " property_office_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz,"
                + " ?::timestamptz, 60, 'CONFIRMED', ?) RETURNING id",
            Long.class,
            activeConditionId(approvalId),
            approvalId,
            propertyId,
            staff.officeId(),
            staff.membershipId(),
            coordinationId,
            candidateId,
            start,
            end,
            staff.officeId());
    Long itemId =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_schedule_item (coordination_id, office_id, candidate_property_id,"
                + " scheduled_start_at, scheduled_end_at, sort_order, result, confirmed_request_id)"
                + " VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, 1, 'SCHEDULED', ?)"
                + " RETURNING id",
            Long.class,
            coordinationId,
            staff.officeId(),
            candidateId,
            start,
            end,
            requestId);
    setCoordinationStatus(coordinationId, "CONFIRMED");
    return itemId == null ? -1 : itemId;
  }

  public record TestStaffRef(long officeId, long membershipId) {}
}
