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
class CoordinationCancelControllerTest {

  private static final Long OFFICE_ID = 9901L;
  private static final Long OTHER_OFFICE_ID = 9902L;
  private static final Long USER_ID = 9903L;
  private static final Long MEMBERSHIP_ID = 9904L;
  private static final Long OTHER_OFFICE_USER_ID = 9905L;
  private static final Long OTHER_OFFICE_MEMBERSHIP_ID = 9906L;
  private static final Long COORDINATION_ID = 9907L;
  private static final Long OTHER_OFFICE_COORDINATION_ID = 9908L;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private StaffAuthentication staffAuthentication;

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

    insertOffice(OFFICE_ID, 8901001L);
    insertOffice(OTHER_OFFICE_ID, 8901002L);
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at) VALUES (?, ?, '박직원', now())",
        USER_ID,
        "kakao-cancel-" + USER_ID);
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at) VALUES (?, ?, '박직원2', now())",
        OTHER_OFFICE_USER_ID,
        "kakao-cancel-" + OTHER_OFFICE_USER_ID);
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
        COORDINATION_ID, OFFICE_ID, propertyId, MEMBERSHIP_ID, "TENANT_CHECKING", false);
    insertCoordination(
        OTHER_OFFICE_COORDINATION_ID,
        OTHER_OFFICE_ID,
        otherOfficePropertyId,
        OTHER_OFFICE_MEMBERSHIP_ID,
        "TENANT_CHECKING",
        false);

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
      Long coordinationId,
      Long officeId,
      Long propertyId,
      Long membershipId,
      String status,
      boolean finalized) {
    if (finalized) {
      jdbcTemplate.update(
          "INSERT INTO coordination (id, office_id, property_id, status,"
              + " created_by_membership_id, scheduled_at, confirmed_at, created_at, updated_at)"
              + " VALUES (?, ?, ?, ?, ?, now(), now(), now(), now())",
          coordinationId,
          officeId,
          propertyId,
          status,
          membershipId);
      return;
    }
    jdbcTemplate.update(
        "INSERT INTO coordination (id, office_id, property_id, status, created_by_membership_id,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
        coordinationId,
        officeId,
        propertyId,
        status,
        membershipId);
  }

  private void setStatus(Long coordinationId, String status) {
    jdbcTemplate.update("UPDATE coordination SET status = ? WHERE id = ?", status, coordinationId);
  }

  @Test
  void 세입자_확인중_조율_건을_취소하면_취소_상태로_전이한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT cancelled_at IS NOT NULL FROM coordination WHERE id = ?",
                Boolean.class,
                COORDINATION_ID))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?"
                    + " AND from_status = 'TENANT_CHECKING' AND to_status = 'CANCELLED'"
                    + " AND source = 'STAFF'",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }

  @Test
  void 구매자_확인중_조율_건도_취소할_수_있다() throws Exception {
    setStatus(COORDINATION_ID, "BUYER_CHECKING");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void 확정_완료된_조율_건은_취소할_수_없다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'SCHEDULE_CONFIRMED', scheduled_at = now(),"
            + " confirmed_at = now() WHERE id = ?",
        COORDINATION_ID);

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 임장_완료된_조율_건은_취소할_수_없다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'VISIT_COMPLETED', scheduled_at = now(),"
            + " confirmed_at = now() WHERE id = ?",
        COORDINATION_ID);

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 이미_취소된_조율_건을_다시_취소할_수_없다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 다른_사무소_조율_건이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", OTHER_OFFICE_COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 존재하지_않는_조율_건이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/cancel", 999999L)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 인증되지_않은_요청은_401을_반환한다() throws Exception {
    mockMvc
        .perform(post("/api/coordinations/{coordinationId}/cancel", COORDINATION_ID).with(csrf()))
        .andExpect(status().isUnauthorized());
  }
}
