package com.sairo.be.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

public abstract class VisitRequestIntegrationTestBase extends ApproverIntegrationTestBase {

  protected record RequestFixture(
      TestData.Staff owner,
      Cookie cookie,
      long customer,
      long coordinationId,
      long propertyId,
      long candidateId,
      long approvalId) {

    public TestStaffRef ref() {
      return new TestStaffRef(owner.office().getId(), owner.membership().getId());
    }
  }

  protected RequestFixture requestFixture() throws Exception {
    return requestFixture(newStaff(OfficeMembershipRole.OWNER), true, true);
  }

  protected RequestFixture requestFixture(
      TestData.Staff owner, boolean withApproval, boolean withCondition) throws Exception {
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long propertyId = newProperty(cookie);
    JsonNode created =
        createCoordination(cookie, customer, null, today(), today().plusDays(10), propertyId);
    long coordinationId = created.get("coordinationId").longValue();
    long candidateId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ?",
            Long.class,
            coordinationId);
    long approvalId = 0;
    if (withApproval) {
      approvalId = registerApproval(cookie, propertyId, null, -1, 30);
      if (withCondition) {
        mockMvc
            .perform(
                jsonPutAs(
                    approvalsUrl(propertyId) + "/" + approvalId + "/approver-conditions/proxy",
                    conditionJson(true),
                    cookie,
                    null))
            .andExpect(status().isOk());
      }
    }
    submitCustomerSlots(cookie, coordinationId, today().plusDays(3), 10, 16);
    setCoordinationStatus(coordinationId, "FINAL_CONFIRMATION_REQUIRED");
    return new RequestFixture(
        owner, cookie, customer, coordinationId, propertyId, candidateId, approvalId);
  }

  protected void submitCustomerSlots(
      Cookie cookie, long coordinationId, LocalDate day, int fromHour, int toHour)
      throws Exception {
    String token = issueToken(cookie, coordinationId);
    String slot =
        "{\"startsAt\":\""
            + day
            + "T"
            + String.format("%02d", fromHour)
            + ":00:00+09:00\",\"endsAt\":\""
            + day
            + "T"
            + String.format("%02d", toHour)
            + ":00:00+09:00\"}";
    mockMvc
        .perform(
            jsonPostAs(
                "/api/public/customer-visit-links/" + token + "/available-times",
                "{\"timeSlots\":["
                    + slot
                    + "],\"acknowledgedNoticeVersion\":\""
                    + NOTICE_VERSION
                    + "\"}",
                null))
        .andExpect(status().isOk());
  }

  protected static String requestUrl(long coordinationId) {
    return "/api/visit-coordinations/" + coordinationId + "/visit-requests";
  }

  protected String requestBody(long candidateId, LocalDate day, String from, String to) {
    return requestBody(candidateId, day, from, to, "30", "");
  }

  protected String requestBody(
      long candidateId, LocalDate day, String from, String to, String duration, String extra) {
    return "{\"candidatePropertyId\":"
        + candidateId
        + ",\"requestedStartAt\":\""
        + day
        + "T"
        + from
        + ":00+09:00\",\"requestedEndAt\":\""
        + day
        + "T"
        + to
        + ":00+09:00\",\"expectedDurationMinutes\":"
        + duration
        + extra
        + "}";
  }

  protected String validBody(RequestFixture f) {
    return requestBody(f.candidateId(), today().plusDays(3), "10:00", "10:30");
  }

  protected long submit(RequestFixture f) throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(requestUrl(f.coordinationId()), validBody(f), f.cookie()))
                .andExpect(status().isCreated())
                .andReturn())
        .get("requestId")
        .longValue();
  }

  protected long insertCoordinationWithCandidate(
      TestStaffRef staff, long customerId, long propertyId, String coordinationStatus) {
    return insertCoordinationWithCandidate(
        staff, customerId, propertyId, staff.officeId(), coordinationStatus);
  }

  protected long insertCoordinationWithCandidate(
      TestStaffRef staff,
      long customerId,
      long propertyId,
      long propertyOfficeId,
      String coordinationStatus) {
    Long coordinationId =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id,"
                + " status, period_start_date, period_end_date) VALUES (?, ?, ?, ?,"
                + " current_date, current_date + 10) RETURNING id",
            Long.class,
            staff.officeId(),
            customerId,
            staff.membershipId(),
            coordinationStatus);
    Long candidateId =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
                + " property_office_id, sort_order) VALUES (?, ?, ?, ?, 1) RETURNING id",
            Long.class,
            coordinationId,
            staff.officeId(),
            propertyId,
            propertyOfficeId);
    return candidateId == null ? -1 : candidateId;
  }

  protected record ExtraCandidate(long propertyId, long candidateId, long approvalId) {}

  protected ExtraCandidate addCandidateWithApproval(RequestFixture f) throws Exception {
    long property = newProperty(f.cookie());
    mockMvc
        .perform(
            jsonPostAs(
                "/api/visit-coordinations/" + f.coordinationId() + "/candidate-properties",
                "{\"propertyId\":" + property + "}",
                f.cookie()))
        .andExpect(status().isCreated());
    long approvalId = registerApproval(f.cookie(), property, null, -1, 30);
    mockMvc
        .perform(
            jsonPutAs(
                approvalsUrl(property) + "/" + approvalId + "/approver-conditions/proxy",
                conditionJson(true),
                f.cookie(),
                null))
        .andExpect(status().isOk());
    long candidateId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_candidate_property WHERE coordination_id = ? AND property_id ="
                + " ?",
            Long.class,
            f.coordinationId(),
            property);
    return new ExtraCandidate(property, candidateId, approvalId);
  }

  protected long coordinationOfCandidate(long candidateId) {
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT coordination_id FROM visit_candidate_property WHERE id = ?",
            Long.class,
            candidateId);
    return id == null ? -1 : id;
  }

  protected long insertRequestRow(
      TestStaffRef applicant,
      long candidateId,
      long propertyId,
      long approvalId,
      long propertyOfficeId,
      String status,
      String startExpression) {
    boolean closed =
        Stream.of("REVALIDATION_FAILED", "TIME_CLOSED", "EXPIRED", "WITHDRAWN", "CANCELLED")
            .collect(Collectors.toSet())
            .contains(status);
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_request (condition_id, approval_id, property_id,"
                + " applicant_office_id, applicant_membership_id, coordination_id,"
                + " candidate_property_id, requested_start_at, requested_end_at,"
                + " expected_duration_minutes, status, property_office_id, closed_at) VALUES (?,"
                + " ?, ?, ?, ?, ?, ?, "
                + startExpression
                + ", ("
                + startExpression
                + ") + interval '30 minutes', 30, ?, ?, "
                + (closed ? "now()" : "NULL")
                + ") RETURNING id",
            Long.class,
            activeConditionId(approvalId),
            approvalId,
            propertyId,
            applicant.officeId(),
            applicant.membershipId(),
            coordinationOfCandidate(candidateId),
            candidateId,
            status,
            propertyOfficeId);
    return id == null ? -1 : id;
  }

  protected String requestStatus(long requestId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM visit_request WHERE id = ?", String.class, requestId);
  }
}
