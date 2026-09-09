package com.sairo.be.domain.coordination.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BuyerCoordinationControllerTest {

  private static final Long OFFICE_ID = 9501L;
  private static final Long OTHER_OFFICE_ID = 9502L;
  private static final Long USER_ID = 9503L;
  private static final Long MEMBERSHIP_ID = 9504L;
  private static final Long OTHER_OFFICE_USER_ID = 9508L;
  private static final Long OTHER_OFFICE_MEMBERSHIP_ID = 9507L;
  private static final Long COORDINATION_ID = 9505L;
  private static final Long OTHER_OFFICE_COORDINATION_ID = 9506L;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private StaffAuthentication staffAuthentication;
  private List<Long> candidateTimeIds;

  @BeforeEach
  void seed() {
    jdbcTemplate.update(
        "UPDATE coordination SET selected_buyer_response_id = NULL, confirmed_candidate_time_id = NULL");
    jdbcTemplate.update("DELETE FROM customer_response_candidate");
    jdbcTemplate.update("DELETE FROM coordination_status_history");
    jdbcTemplate.update("DELETE FROM customer_response_link");
    jdbcTemplate.update("DELETE FROM coordination_customer_response");
    jdbcTemplate.update("DELETE FROM coordination_candidate_time");
    jdbcTemplate.update("DELETE FROM coordination");
    jdbcTemplate.update("DELETE FROM property");
    jdbcTemplate.update("DELETE FROM office_membership");
    jdbcTemplate.update("DELETE FROM app_user");
    jdbcTemplate.update("DELETE FROM office");

    insertOffice(OFFICE_ID, 8501001L);
    insertOffice(OTHER_OFFICE_ID, 8501002L);
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at) VALUES (?, ?, '박직원', now())",
        USER_ID,
        "kakao-" + USER_ID);
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at) VALUES (?, ?, '박직원2', now())",
        OTHER_OFFICE_USER_ID,
        "kakao-" + OTHER_OFFICE_USER_ID);
    jdbcTemplate.update(
        "INSERT INTO office_membership (id, user_id, office_id, role, status, reviewed_at)"
            + " VALUES (?, ?, ?, 'STAFF', 'APPROVED', now())",
        MEMBERSHIP_ID,
        USER_ID,
        OFFICE_ID);
    jdbcTemplate.update(
        "INSERT INTO office_membership (id, user_id, office_id, role, status, reviewed_at)"
            + " VALUES (?, ?, ?, 'STAFF', 'APPROVED', now())",
        OTHER_OFFICE_MEMBERSHIP_ID,
        OTHER_OFFICE_USER_ID,
        OTHER_OFFICE_ID);
    Long propertyId = insertProperty(OFFICE_ID, "서울시 강남구 테헤란로 1");
    Long otherOfficePropertyId = insertProperty(OTHER_OFFICE_ID, "서울시 서초구 반포대로 1");

    insertCoordination(
        COORDINATION_ID, OFFICE_ID, propertyId, MEMBERSHIP_ID, "BUYER_DELIVERY_REQUIRED");
    insertCoordination(
        OTHER_OFFICE_COORDINATION_ID,
        OTHER_OFFICE_ID,
        otherOfficePropertyId,
        OTHER_OFFICE_MEMBERSHIP_ID,
        "BUYER_DELIVERY_REQUIRED");

    Instant base = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    candidateTimeIds =
        List.of(
            insertCandidateTime(COORDINATION_ID, base),
            insertCandidateTime(COORDINATION_ID, base.plus(Duration.ofHours(1))),
            insertCandidateTime(COORDINATION_ID, base.plus(Duration.ofHours(2))));

    staffAuthentication =
        new StaffAuthentication(new StaffPrincipal(USER_ID, MEMBERSHIP_ID, OFFICE_ID, "박직원"));
  }

  private void insertOffice(Long officeId, Long businessRegistrationNumber) {
    jdbcTemplate.update(
        "INSERT INTO office (id, name, representative_name, business_registration_number,"
            + " real_estate_license_number, phone, address, created_at)"
            + " VALUES (?, '사이로 데모 사무소', '김대표', ?, ?, '02-1234-5678', '서울시 강남구', now())",
        officeId,
        businessRegistrationNumber,
        "1234567890" + officeId);
  }

  private Long insertProperty(Long officeId, String address) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO property (office_id, address, deal_type, created_at)"
            + " VALUES (?, ?, 'JEONSE', now()) RETURNING id",
        Long.class,
        officeId,
        address);
  }

  private void insertCoordination(
      Long coordinationId, Long officeId, Long propertyId, Long membershipId, String status) {
    jdbcTemplate.update(
        "INSERT INTO coordination (id, office_id, property_id, status, created_by_membership_id,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
        coordinationId,
        officeId,
        propertyId,
        status,
        membershipId);
  }

  private Long insertCandidateTime(Long coordinationId, Instant startsAt) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_candidate_time (coordination_id, starts_at)"
            + " VALUES (?, ?) RETURNING id",
        Long.class,
        coordinationId,
        Timestamp.from(startsAt));
  }

  private Long insertTenantResponse(Long coordinationId, String result) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_customer_response"
            + " (coordination_id, role, customer_name, customer_phone, result, reset_count, created_at)"
            + " VALUES (?, 'TENANT', '김세입', '010-1111-2222', ?, 0, now()) RETURNING id",
        Long.class,
        coordinationId,
        result);
  }

  private void approveCandidate(Long responseId, Long coordinationId, Long candidateTimeId) {
    jdbcTemplate.update(
        "INSERT INTO customer_response_candidate (response_id, coordination_id, candidate_time_id,"
            + " is_selected) VALUES (?, ?, ?, true)",
        responseId,
        coordinationId,
        candidateTimeId);
  }

  private void seedApprovedTenant() {
    Long tenantResponseId = insertTenantResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");
    approveCandidate(tenantResponseId, COORDINATION_ID, candidateTimeIds.get(0));
    approveCandidate(tenantResponseId, COORDINATION_ID, candidateTimeIds.get(1));
  }

  private String extractLong(String body, String field) {
    Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException(field + " not found in response: " + body);
    }
    return matcher.group(1);
  }

  private String extractString(String body, String field) {
    Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException(field + " not found in response: " + body);
    }
    return matcher.group(1);
  }

  @Test
  void 세입자_승인후_첫_구매자를_추가하면_구매자_응답과_링크가_생성되고_구매자확인중으로_전이한다() throws Exception {
    seedApprovedTenant();

    String responseBody =
        mockMvc
            .perform(
                post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                    .with(authentication(staffAuthentication))
                    .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.buyerResponseId").exists())
            .andExpect(jsonPath("$.customerLinkUrl").exists())
            .andExpect(jsonPath("$.linkExpiresAt").exists())
            .andExpect(jsonPath("$.coordinationStatus").value("BUYER_CHECKING"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    Long buyerResponseId = Long.valueOf(extractLong(responseBody, "buyerResponseId"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("BUYER_CHECKING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT role FROM coordination_customer_response WHERE id = ?",
                String.class,
                buyerResponseId))
        .isEqualTo("BUYER");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_response_candidate WHERE response_id = ?",
                Integer.class,
                buyerResponseId))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customer_response_candidate WHERE response_id = ? AND is_selected = true",
                Integer.class,
                buyerResponseId))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?"
                    + " AND from_status = 'BUYER_DELIVERY_REQUIRED' AND to_status = 'BUYER_CHECKING'"
                    + " AND source = 'STAFF'",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }

  @Test
  void 구매희망자를_두번째로_추가하려하면_409를_반환한다() throws Exception {
    seedApprovedTenant();

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("BUYER_ALREADY_EXISTS"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_customer_response WHERE coordination_id = ? AND role = 'BUYER'",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }

  @Test
  void 세입자가_아직_제출하기_전이면_409를_반환한다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'TENANT_CHECKING' WHERE id = ?", COORDINATION_ID);
    insertTenantResponse(COORDINATION_ID, "WAITING");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 이미_확정된_조율_건에는_구매희망자를_추가할_수_없다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'SCHEDULE_CONFIRMED', scheduled_at = now(),"
            + " confirmed_at = now() WHERE id = ?",
        COORDINATION_ID);
    insertTenantResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 임장_완료된_조율_건에는_구매희망자를_추가할_수_없다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'VISIT_COMPLETED', scheduled_at = now(),"
            + " confirmed_at = now() WHERE id = ?",
        COORDINATION_ID);
    insertTenantResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 취소된_조율_건에는_구매희망자를_추가할_수_없다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?",
        COORDINATION_ID);
    insertTenantResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 다른_사무소_조율_건이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", OTHER_OFFICE_COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 존재하지_않는_조율_건이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/buyers", 999999L)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 인증되지_않은_요청은_401을_반환한다() throws Exception {
    mockMvc
        .perform(post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID).with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 구매자는_세입자가_승인하지_않은_후보를_제출하면_422를_반환한다() throws Exception {
    seedApprovedTenant();

    String createBody =
        mockMvc
            .perform(
                post("/api/coordinations/{coordinationId}/buyers", COORDINATION_ID)
                    .with(authentication(staffAuthentication))
                    .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String customerLinkUrl = extractString(createBody, "customerLinkUrl");
    String token = customerLinkUrl.substring(customerLinkUrl.lastIndexOf('/') + 1);

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(2) + "]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_ALLOWED"));
  }
}
