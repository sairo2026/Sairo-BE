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
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
class CoordinationControllerTest {

  private static final Long OFFICE_ID = 9601L;
  private static final Long OTHER_OFFICE_ID = 9602L;
  private static final Long USER_ID = 9701L;
  private static final Long MEMBERSHIP_ID = 9801L;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  private StaffAuthentication staffAuthentication;
  private Long propertyId;
  private Long otherOfficePropertyId;

  @BeforeEach
  void seed() {
    jdbcTemplate.update("DELETE FROM coordination_status_history");
    jdbcTemplate.update("DELETE FROM customer_response_link");
    jdbcTemplate.update("DELETE FROM coordination_customer_response");
    jdbcTemplate.update("DELETE FROM coordination_candidate_time");
    jdbcTemplate.update("DELETE FROM coordination");
    jdbcTemplate.update("DELETE FROM property");
    jdbcTemplate.update("DELETE FROM office_membership");
    jdbcTemplate.update("DELETE FROM app_user");
    jdbcTemplate.update("DELETE FROM office");

    insertOffice(OFFICE_ID, "11223344556");
    insertOffice(OTHER_OFFICE_ID, "99887766554");
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

    propertyId = insertProperty(OFFICE_ID, "서울시 강남구 테헤란로 1");
    otherOfficePropertyId = insertProperty(OTHER_OFFICE_ID, "서울시 서초구 반포대로 1");

    staffAuthentication =
        new StaffAuthentication(new StaffPrincipal(USER_ID, MEMBERSHIP_ID, OFFICE_ID, "박직원"));
  }

  private void insertOffice(Long officeId, String licenseNumber) {
    jdbcTemplate.update(
        "INSERT INTO office (id, name, representative_name, business_registration_number,"
            + " real_estate_license_number, phone, address, created_at)"
            + " VALUES (?, '사이로 데모 사무소', '김대표', ?, ?, '02-1234-5678', '서울시 강남구', now())",
        officeId,
        officeId + 1000000,
        licenseNumber);
  }

  private Long insertProperty(Long officeId, String address) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO property (office_id, address, deal_type, created_at)"
            + " VALUES (?, ?, 'JEONSE', now()) RETURNING id",
        Long.class,
        officeId,
        address);
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

  private List<Instant> distinctCandidateTimes(int count) {
    Instant base = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(i -> base.plus(Duration.ofHours(i)))
        .toList();
  }

  private String requestBody(String tenantName, String tenantPhone, List<Instant> startTimes) {
    return "{\"tenantName\":\""
        + tenantName
        + "\",\"tenantPhone\":\""
        + tenantPhone
        + "\",\"candidateTimes\":"
        + candidateTimesJson(startTimes)
        + "}";
  }

  @Test
  void 중복없는_후보시간_3개_제출하면_조율_건과_세입자_응답과_링크가_생성된다() throws Exception {
    List<Instant> candidateTimes = distinctCandidateTimes(3);

    String responseBody =
        mockMvc
            .perform(
                post("/api/properties/{propertyId}/coordinations", propertyId)
                    .with(authentication(staffAuthentication))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody("김세입", "010-1111-2222", candidateTimes)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("TENANT_CHECKING"))
            .andExpect(jsonPath("$.coordinationId").exists())
            .andExpect(jsonPath("$.tenantResponseId").exists())
            .andExpect(jsonPath("$.customerLinkUrl").exists())
            .andExpect(jsonPath("$.linkExpiresAt").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Long coordinationId = extractLong(responseBody, "coordinationId");
    Long tenantResponseId = extractLong(responseBody, "tenantResponseId");
    String customerLinkUrl = extractString(responseBody, "customerLinkUrl");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, coordinationId))
        .isEqualTo("TENANT_CHECKING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_candidate_time WHERE coordination_id = ?",
                Integer.class,
                coordinationId))
        .isEqualTo(3);

    Map<String, Object> response =
        jdbcTemplate.queryForMap(
            "SELECT role, customer_name, customer_phone, result FROM coordination_customer_response"
                + " WHERE id = ?",
            tenantResponseId);
    assertThat(response.get("role")).isEqualTo("TENANT");
    assertThat(response.get("customer_name")).isEqualTo("김세입");
    assertThat(response.get("customer_phone")).isEqualTo("010-1111-2222");
    assertThat(response.get("result")).isEqualTo("WAITING");

    Map<String, Object> link =
        jdbcTemplate.queryForMap(
            "SELECT token_hash, issued_at, expires_at FROM customer_response_link"
                + " WHERE response_id = ?",
            tenantResponseId);
    String token = customerLinkUrl.substring(customerLinkUrl.lastIndexOf('/') + 1);
    assertThat(link.get("token_hash")).isEqualTo(sha256Hex(token));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history"
                    + " WHERE coordination_id = ? AND from_status IS NULL AND to_status = 'TENANT_CHECKING' AND source = 'STAFF'",
                Integer.class,
                coordinationId))
        .isEqualTo(1);
  }

  @Test
  void 같은_시각을_2번_포함한_후보시간이면_400을_반환한다() throws Exception {
    Instant duplicated = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);

    mockMvc
        .perform(
            post("/api/properties/{propertyId}/coordinations", propertyId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("김세입", "010-1111-2222", List.of(duplicated, duplicated))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM coordination", Integer.class))
        .isZero();
  }

  @Test
  void 후보시간이_0개이면_400을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties/{propertyId}/coordinations", propertyId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantName\":\"김세입\",\"tenantPhone\":\"010-1111-2222\",\"candidateTimes\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 후보시간이_11개이면_400을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties/{propertyId}/coordinations", propertyId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("김세입", "010-1111-2222", distinctCandidateTimes(11))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
  }

  @Test
  void 존재하지_않는_매물이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties/{propertyId}/coordinations", 999999L)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("김세입", "010-1111-2222", distinctCandidateTimes(1))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 다른_사무소_매물이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties/{propertyId}/coordinations", otherOfficePropertyId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("김세입", "010-1111-2222", distinctCandidateTimes(1))))
        .andExpect(status().isNotFound());
  }

  @Test
  void 인증되지_않은_요청은_401을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/properties/{propertyId}/coordinations", propertyId)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("김세입", "010-1111-2222", distinctCandidateTimes(1))))
        .andExpect(status().isUnauthorized());
  }

  private Long extractLong(String body, String field) {
    Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException(field + " not found in response: " + body);
    }
    return Long.valueOf(matcher.group(1));
  }

  private String extractString(String body, String field) {
    Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException(field + " not found in response: " + body);
    }
    return matcher.group(1);
  }

  private String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
