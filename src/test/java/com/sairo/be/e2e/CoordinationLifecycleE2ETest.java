package com.sairo.be.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
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
class CoordinationLifecycleE2ETest {

  private static final Long OFFICE_ID = 9001L;
  private static final Long USER_ID = 9002L;
  private static final Long MEMBERSHIP_ID = 9003L;

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

    jdbcTemplate.update(
        "INSERT INTO office (id, name, representative_name, business_registration_number,"
            + " real_estate_license_number, phone, address, created_at)"
            + " VALUES (?, '사이로 데모 사무소', '김대표', ?, '11122233344', '02-1234-5678',"
            + " '서울시 강남구', now())",
        OFFICE_ID,
        OFFICE_ID + 1000000);
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at) VALUES (?, ?, '박직원', now())",
        USER_ID,
        "kakao-" + USER_ID);
    jdbcTemplate.update(
        "INSERT INTO office_membership (id, user_id, office_id, role, status, reviewed_at)"
            + " VALUES (?, ?, ?, 'STAFF', 'APPROVED', now())",
        MEMBERSHIP_ID,
        USER_ID,
        OFFICE_ID);

    staffAuthentication =
        new StaffAuthentication(new StaffPrincipal(USER_ID, MEMBERSHIP_ID, OFFICE_ID, "박직원"));
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

  private String tokenFromLink(String customerLinkUrl) {
    return customerLinkUrl.substring(customerLinkUrl.lastIndexOf('/') + 1);
  }

  private String candidateTimesJson(List<Instant> startTimes) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < startTimes.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"startsAt\":\"").append(startTimes.get(i)).append("\"}");
    }
    return sb.append(']').toString();
  }

  @Test
  void 매물등록부터_임장완료까지_조율_생애주기_전체가_정상적으로_전이한다() throws Exception {
    String propertyBody =
        mockMvc
            .perform(
                post("/api/properties")
                    .with(authentication(staffAuthentication))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"address\":\"서울시 강남구 테헤란로 1\",\"dealType\":\"JEONSE\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long propertyId = Long.valueOf(extractLong(propertyBody, "propertyId"));

    Instant base = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    List<Instant> candidateStartTimes =
        List.of(base, base.plus(Duration.ofHours(1)), base.plus(Duration.ofHours(2)));

    String coordinationBody =
        mockMvc
            .perform(
                post("/api/properties/{propertyId}/coordinations", propertyId)
                    .with(authentication(staffAuthentication))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"tenantName\":\"김세입\",\"tenantPhone\":\"010-1111-2222\",\"candidateTimes\":"
                            + candidateTimesJson(candidateStartTimes)
                            + "}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("TENANT_CHECKING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long coordinationId = Long.valueOf(extractLong(coordinationBody, "coordinationId"));
    String tenantToken = tokenFromLink(extractString(coordinationBody, "customerLinkUrl"));

    List<Long> candidateTimeIds =
        jdbcTemplate.queryForList(
            "SELECT id FROM coordination_candidate_time WHERE coordination_id = ? ORDER BY starts_at",
            Long.class,
            coordinationId);
    assertThat(candidateTimeIds).hasSize(3);

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", tenantToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"candidateTimeIds\":["
                        + candidateTimeIds.get(0)
                        + ","
                        + candidateTimeIds.get(1)
                        + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("AVAILABLE_SUBMITTED"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, coordinationId))
        .isEqualTo("BUYER_DELIVERY_REQUIRED");

    String buyerCreateBody =
        mockMvc
            .perform(
                post("/api/coordinations/{coordinationId}/buyers", coordinationId)
                    .with(authentication(staffAuthentication))
                    .with(csrf()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.coordinationStatus").value("BUYER_CHECKING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Long buyerResponseId = Long.valueOf(extractLong(buyerCreateBody, "buyerResponseId"));
    String buyerToken = tokenFromLink(extractString(buyerCreateBody, "customerLinkUrl"));

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", buyerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("AVAILABLE_SUBMITTED"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, coordinationId))
        .isEqualTo("FINAL_CONFIRMATION_REQUIRED");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/confirm", coordinationId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"buyerResponseId\":"
                        + buyerResponseId
                        + ",\"candidateTimeId\":"
                        + candidateTimeIds.get(0)
                        + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULE_CONFIRMED"))
        .andExpect(jsonPath("$.confirmedBuyerResponseId").value(buyerResponseId));

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/visit-complete", coordinationId)
                .with(authentication(staffAuthentication))
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VISIT_COMPLETED"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, coordinationId))
        .isEqualTo("VISIT_COMPLETED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result FROM coordination_customer_response WHERE id = ?",
                String.class,
                buyerResponseId))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?",
                Integer.class,
                coordinationId))
        .isEqualTo(6);

    mockMvc
        .perform(
            get("/api/coordinations/{coordinationId}", coordinationId)
                .with(authentication(staffAuthentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VISIT_COMPLETED"))
        .andExpect(jsonPath("$.scheduledAt").exists())
        .andExpect(jsonPath("$.confirmedAt").exists());
  }
}
