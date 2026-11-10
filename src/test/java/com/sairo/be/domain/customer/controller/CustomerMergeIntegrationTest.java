package com.sairo.be.domain.customer.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class CustomerMergeIntegrationTest extends CustomerIntegrationTestBase {

  private static final String URL = "/api/customers/merge";

  private String mergeBody(long a, long b, long mergedVersion, String extra) {
    return "{\"primaryCustomerId\":"
        + a
        + ",\"mergedCustomerId\":"
        + b
        + ",\"conflictResolutionNote\":\"같은 사람 중복 등록\",\"mergedCustomerVersion\":"
        + mergedVersion
        + extra
        + "}";
  }

  private long consult(long customerId, long membershipId, String type) {
    return createConsultationRow(customerId, membershipId, type);
  }

  @Test
  void 병합하면_선택한_필드만_옮기고_두_고객_버전을_1씩_올리며_로그를_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "남는고객", "010-1111-1111", m);
    long b = createCustomer(cookie, "합쳐진고객", "010-2222-2222", m);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/customers/" + b,
                "{\"memo\":\"B 메모\",\"email\":\"b@example.com\"}",
                cookie,
                1))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            jsonPostAs(
                    URL,
                    mergeBody(
                        a,
                        b,
                        2,
                        ",\"fieldChoices\":{\"phone\":\"MERGED\",\"memo\":\"MERGED\",\"name\":\"PRIMARY\"}"),
                    cookie)
                .header("If-Match", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.primaryCustomerId").value(a))
        .andExpect(jsonPath("$.mergedCustomerId").value(b))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.mergedAt").isString());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT name, phone, memo, email, version FROM customer WHERE id = ?", a);
    assertThat(row)
        .containsEntry("name", "남는고객")
        .containsEntry("phone", "010-2222-2222")
        .containsEntry("memo", "B 메모")
        .containsEntry("email", null)
        .containsEntry("version", 2L);
    assertThat(customerVersion(b)).isEqualTo(3);
    Map<String, Object> log =
        jdbcTemplate.queryForMap(
            "SELECT primary_customer_id, merged_customer_id, executed_by_membership_id,"
                + " conflict_resolution_note FROM customer_merge_log");
    assertThat(log)
        .containsEntry("primary_customer_id", a)
        .containsEntry("merged_customer_id", b)
        .containsEntry("executed_by_membership_id", m)
        .containsEntry("conflict_resolution_note", "같은 사람 중복 등록");
    mockMvc
        .perform(authedGet("/api/customers/" + b, cookie))
        .andExpect(jsonPath("$.status").value("MERGED"))
        .andExpect(jsonPath("$.mergedIntoCustomerId").value(a))
        .andExpect(jsonPath("$.phone").value("010-2222-2222"));
    mockMvc
        .perform(authedGet("/api/customers", cookie))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].customerId").value(a));
  }

  @Test
  void 연속_병합은_최종_고객으로_이어지고_이미_병합된_고객은_다시_병합할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "A", null, m);
    long b = createCustomer(cookie, "B", null, m);
    long c = createCustomer(cookie, "C", null, m);
    mockMvc
        .perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie).header("If-Match", "1"))
        .andExpect(status().isOk());
    mockMvc
        .perform(jsonPostAs(URL, mergeBody(c, a, 2, ""), cookie).header("If-Match", "1"))
        .andExpect(status().isOk());
    mockMvc
        .perform(authedGet("/api/customers/" + b, cookie))
        .andExpect(jsonPath("$.mergedIntoCustomerId").value(c));
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(c, b, 1, ""), cookie).header("If-Match", "2")),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(b, c, 2, ""), cookie).header("If-Match", "1")),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(c, c, 2, ""), cookie).header("If-Match", "2")),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 대표만_실행할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie agent = cookieOf(staffIn(owner.office(), OfficeMembershipRole.AGENT));
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(1, 2, 1, ""), agent).header("If-Match", "1")),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(1, 2, 1, ""), deputy).header("If-Match", "1")),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(1, 2, 1, ""), null).header("If-Match", "1")),
        ErrorCode.UNAUTHENTICATED);
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(1, 2, 1, ""), plain).header("If-Match", "1")),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 잘못된_요청은_각각_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "A", null, m);
    long b = createCustomer(cookie, "B", null, m);
    long foreign = createCustomer(cookieOf(other), "타사", null, other.membership().getId());
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie).header("If-Match", "7")),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(a, b, 7, ""), cookie).header("If-Match", "1")),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(
            jsonPostAs(URL, mergeBody(a, 99999, 1, ""), cookie).header("If-Match", "1")),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(URL, mergeBody(a, foreign, 1, ""), cookie).header("If-Match", "1")),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                    URL,
                    "{\"primaryCustomerId\":"
                        + a
                        + ",\"mergedCustomerId\":"
                        + b
                        + ",\"mergedCustomerVersion\":1}",
                    cookie)
                .header("If-Match", "1")),
        "conflictResolutionNote",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                    URL,
                    "{\"primaryCustomerId\":"
                        + a
                        + ",\"conflictResolutionNote\":\"x\",\"mergedCustomerVersion\":1}",
                    cookie)
                .header("If-Match", "1")),
        "mergedCustomerId",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(URL, mergeBody(a, b, 1, ",\"x\":1"), cookie).header("If-Match", "1")),
        "x",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(URL, mergeBody(a, b, 1, ",\"fieldChoices\":{\"phone\":\"BOTH\"}"), cookie)
                .header("If-Match", "1")),
        "fieldChoices.phone",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(URL, mergeBody(a, b, 1, ",\"fieldChoices\":{\"role\":\"MERGED\"}"), cookie)
                .header("If-Match", "1")),
        "fieldChoices.role",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(a, b, 0, ""), cookie).header("If-Match", "1")),
        "mergedCustomerVersion",
        "OUT_OF_RANGE");
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", b);
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie).header("If-Match", "1")),
        ErrorCode.CUSTOMER_ARCHIVED);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customer_merge_log", Long.class))
        .isZero();
  }

  @Test
  void 두_고객_모두_최초문의가_있으면_유지할_상담을_골라야_하고_나머지는_후속으로_정정된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "A", null, m);
    long b = createCustomer(cookie, "B", null, m);
    long aFirst = consult(a, m, "FIRST_INQUIRY");
    long bFirst = consult(b, m, "FIRST_INQUIRY");
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie).header("If-Match", "1")),
        ErrorCode.INVALID_REQUEST);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(URL, mergeBody(a, b, 1, ",\"keepFirstInquiryId\":99999"), cookie)
                .header("If-Match", "1")),
        "keepFirstInquiryId",
        "NOT_ALLOWED");
    mockMvc
        .perform(
            jsonPostAs(URL, mergeBody(a, b, 1, ",\"keepFirstInquiryId\":" + bFirst), cookie)
                .header("If-Match", "1"))
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT consultation_type FROM customer_consultation WHERE id = ?",
                String.class,
                aFirst))
        .isEqualTo("FOLLOWUP");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT consultation_type FROM customer_consultation WHERE id = ?",
                String.class,
                bFirst))
        .isEqualTo("FIRST_INQUIRY");
    Map<String, Object> revision =
        jdbcTemplate.queryForMap(
            "SELECT previous_consultation_type, corrected_by_membership_id FROM"
                + " customer_consultation_revision WHERE consultation_id = "
                + aFirst);
    assertThat(revision)
        .containsEntry("previous_consultation_type", "FIRST_INQUIRY")
        .containsEntry("corrected_by_membership_id", m);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT version FROM customer_consultation WHERE id = ?", Long.class, aFirst))
        .isEqualTo(2);
    mockMvc
        .perform(authedGet("/api/customers/" + a + "/consultations", cookie))
        .andExpect(jsonPath("$.items.length()").value(2));
  }

  @Test
  void 한쪽만_최초문의가_있으면_그대로_유지한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "A", null, m);
    long b = createCustomer(cookie, "B", null, m);
    long bFirst = consult(b, m, "FIRST_INQUIRY");
    mockMvc
        .perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie).header("If-Match", "1"))
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT consultation_type FROM customer_consultation WHERE id = ?",
                String.class,
                bFirst))
        .isEqualTo("FIRST_INQUIRY");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_consultation_revision", Long.class))
        .isZero();
  }

  @Test
  void 종료되지_않은_확정_방문이_겹치면_409_SCHEDULE_CONFLICT이고_과거_방문은_막지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long officeId = owner.office().getId();
    long a = createCustomer(cookie, "A", null, m);
    long b = createCustomer(cookie, "B", null, m);
    long property = createProperty(cookie, "방문 매물", categoryIdOf("APARTMENT"));
    long ca = coordination(officeId, a, m, property);
    long cb = coordination(officeId, b, m, property);
    scheduleItem(ca, officeId, "2026-12-02 10:00+09", "2026-12-02 10:30+09", "SCHEDULED");
    scheduleItem(cb, officeId, "2026-12-02 10:15+09", "2026-12-02 10:45+09", "SCHEDULED");
    assertError(
        mockMvc.perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie).header("If-Match", "1")),
        ErrorCode.SCHEDULE_CONFLICT);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customer_merge_log", Long.class))
        .isZero();
    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET result = 'CANCELLED' WHERE coordination_id = ?", cb);
    mockMvc
        .perform(jsonPostAs(URL, mergeBody(a, b, 1, ""), cookie).header("If-Match", "1"))
        .andExpect(status().isOk());
  }

  private long coordination(long officeId, long customerId, long membershipId, long propertyId) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,"
                + " period_start_date, period_end_date) VALUES (?, ?, ?, 'CONFIRMED', '2026-12-01',"
                + " '2026-12-05') RETURNING id",
            Long.class,
            officeId,
            customerId,
            membershipId);
    jdbcTemplate.update(
        "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
            + " property_office_id, sort_order) VALUES (?, ?, ?, ?, 1)",
        id,
        officeId,
        propertyId,
        officeId);
    return id == null ? -1 : id;
  }

  private void scheduleItem(
      long coordinationId, long officeId, String start, String end, String result) {
    jdbcTemplate.update(
        "INSERT INTO visit_schedule_item (coordination_id, office_id, candidate_property_id,"
            + " scheduled_start_at, scheduled_end_at, sort_order, result) SELECT ?, ?, id, ?::timestamptz,"
            + " ?::timestamptz, 1, ? FROM visit_candidate_property WHERE coordination_id = ?",
        coordinationId,
        officeId,
        start,
        end,
        result,
        coordinationId);
  }
}
