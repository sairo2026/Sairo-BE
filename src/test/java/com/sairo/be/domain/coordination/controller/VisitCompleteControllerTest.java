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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class VisitCompleteControllerTest {

  private static final Long OFFICE_ID = 9701L;
  private static final Long OTHER_OFFICE_ID = 9702L;
  private static final Long USER_ID = 9703L;
  private static final Long MEMBERSHIP_ID = 9704L;
  private static final Long OTHER_OFFICE_USER_ID = 9705L;
  private static final Long OTHER_OFFICE_MEMBERSHIP_ID = 9706L;
  private static final Long COORDINATION_ID = 9707L;
  private static final Long OTHER_OFFICE_COORDINATION_ID = 9708L;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private StaffAuthentication staffAuthentication;
  private Instant scheduledAt;
  private Instant confirmedAt;

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

    insertOffice(OFFICE_ID, 8701001L);
    insertOffice(OTHER_OFFICE_ID, 8701002L);
    insertUser(USER_ID, "박직원");
    insertUser(OTHER_OFFICE_USER_ID, "박직원2");
    insertMembership(MEMBERSHIP_ID, USER_ID, OFFICE_ID);
    insertMembership(OTHER_OFFICE_MEMBERSHIP_ID, OTHER_OFFICE_USER_ID, OTHER_OFFICE_ID);

    Long propertyId = insertProperty(OFFICE_ID, "서울시 강남구 테헤란로 1");
    Long otherOfficePropertyId = insertProperty(OTHER_OFFICE_ID, "서울시 서초구 반포대로 1");

    scheduledAt = Instant.now().plus(java.time.Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    confirmedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    insertCoordination(COORDINATION_ID, OFFICE_ID, propertyId, MEMBERSHIP_ID, "SCHEDULE_CONFIRMED");
    insertCoordination(
        OTHER_OFFICE_COORDINATION_ID,
        OTHER_OFFICE_ID,
        otherOfficePropertyId,
        OTHER_OFFICE_MEMBERSHIP_ID,
        "SCHEDULE_CONFIRMED");

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

  private void insertUser(Long userId, String name) {
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at) VALUES (?, ?, ?, now())",
        userId,
        "kakao-" + userId,
        name);
  }

  private void insertMembership(Long membershipId, Long userId, Long officeId) {
    jdbcTemplate.update(
        "INSERT INTO office_membership (id, user_id, office_id, role, status, reviewed_at)"
            + " VALUES (?, ?, ?, 'STAFF', 'APPROVED', now())",
        membershipId,
        userId,
        officeId);
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
        "INSERT INTO coordination (id, office_id, property_id, status, scheduled_at,"
            + " confirmed_at, created_by_membership_id, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())",
        coordinationId,
        officeId,
        propertyId,
        status,
        java.sql.Timestamp.from(scheduledAt),
        java.sql.Timestamp.from(confirmedAt),
        membershipId);
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

  @Test
  void 확정완료_상태에서_임장완료를_호출하면_임장완료로_전이한다() throws Exception {
    Long tenantResponseId = insertTenantResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/visit-complete", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VISIT_COMPLETED"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("VISIT_COMPLETED");
    assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT scheduled_at FROM coordination WHERE id = ?",
                    java.sql.Timestamp.class,
                    COORDINATION_ID)
                .toInstant())
        .isEqualTo(scheduledAt);
    assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT confirmed_at FROM coordination WHERE id = ?",
                    java.sql.Timestamp.class,
                    COORDINATION_ID)
                .toInstant())
        .isEqualTo(confirmedAt);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result FROM coordination_customer_response WHERE id = ?",
                String.class,
                tenantResponseId))
        .isEqualTo("AVAILABLE_SUBMITTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?"
                    + " AND from_status = 'SCHEDULE_CONFIRMED' AND to_status = 'VISIT_COMPLETED'"
                    + " AND source = 'STAFF'",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }

  @Test
  void 확정완료_상태가_아니면_409를_반환한다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'FINAL_CONFIRMATION_REQUIRED' WHERE id = ?",
        COORDINATION_ID);

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/visit-complete", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 이미_임장완료면_409를_반환한다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'VISIT_COMPLETED' WHERE id = ?", COORDINATION_ID);

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/visit-complete", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 다른_사무소_조율_건이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/visit-complete", OTHER_OFFICE_COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 존재하지_않는_조율_건이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/visit-complete", 999999L)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 인증되지_않은_요청은_401을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/visit-complete", COORDINATION_ID)
                .with(csrf()))
        .andExpect(status().isUnauthorized());
  }
}
