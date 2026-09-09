package com.sairo.be.domain.coordination.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.domain.coordination.entity.CustomerResponseLink;
import com.sairo.be.domain.coordination.repository.CustomerResponseLinkRepository;
import com.sairo.be.domain.coordination.service.PublicLinkTokenGenerator;
import com.sairo.be.global.security.StaffAuthentication;
import com.sairo.be.global.security.StaffPrincipal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
class CoordinationOverviewControllerTest {

  private static final Long OFFICE_ID = 9701L;
  private static final Long OTHER_OFFICE_ID = 9702L;
  private static final Long USER_ID = 9703L;
  private static final Long MEMBERSHIP_ID = 9704L;
  private static final Long OTHER_USER_ID = 9705L;
  private static final Long OTHER_MEMBERSHIP_ID = 9706L;
  private static final Long COORDINATION_ID = 9707L;
  private static final Long OTHER_COORDINATION_ID = 9708L;
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerResponseLinkRepository linkRepository;
  @Autowired private PublicLinkTokenGenerator tokenGenerator;

  private StaffAuthentication staffAuthentication;
  private Long propertyId;
  private Long otherPropertyId;
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

    insertOffice(OFFICE_ID, 8701001L);
    insertOffice(OTHER_OFFICE_ID, 8701002L);
    insertUser(USER_ID, "조회 담당자");
    insertUser(OTHER_USER_ID, "다른 담당자");
    insertMembership(MEMBERSHIP_ID, USER_ID, OFFICE_ID);
    insertMembership(OTHER_MEMBERSHIP_ID, OTHER_USER_ID, OTHER_OFFICE_ID);
    propertyId = insertProperty(OFFICE_ID, "서울시 강남구 테헤란로 10", "101호", "강남 매물");
    otherPropertyId = insertProperty(OTHER_OFFICE_ID, "서울시 서초구 반포대로 10", null, null);
    insertCoordination(
        COORDINATION_ID, OFFICE_ID, propertyId, MEMBERSHIP_ID, "TENANT_CHECKING", null);
    insertCoordination(
        OTHER_COORDINATION_ID,
        OTHER_OFFICE_ID,
        otherPropertyId,
        OTHER_MEMBERSHIP_ID,
        "TENANT_CHECKING",
        null);

    Instant base = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    candidateTimeIds =
        List.of(
            insertCandidateTime(COORDINATION_ID, base),
            insertCandidateTime(COORDINATION_ID, base.plus(Duration.ofHours(1))));
    insertCandidateTime(OTHER_COORDINATION_ID, base);
    insertTenantResponse(COORDINATION_ID, "NONE_AVAILABLE", Instant.now());
    insertTenantResponse(OTHER_COORDINATION_ID, "NONE_AVAILABLE", Instant.now());

    staffAuthentication =
        new StaffAuthentication(new StaffPrincipal(USER_ID, MEMBERSHIP_ID, OFFICE_ID, "조회 담당자"));
  }

  @Test
  void 재시작하면_기존_링크와_후보를_폐기하고_응답만_대기로_되돌린다() throws Exception {
    Long tenantResponseId = tenantResponseId(COORDINATION_ID);
    offerCandidate(tenantResponseId, COORDINATION_ID, candidateTimeIds.get(0), true);
    Long buyerResponseId = insertBuyerResponse(COORDINATION_ID, "WAITING");
    offerCandidate(buyerResponseId, COORDINATION_ID, candidateTimeIds.get(0), false);
    String oldToken = issueLink(tenantResponseId, Instant.now()).token();

    String restartBody =
        mockMvc
            .perform(
                post(
                        "/api/coordinations/{coordinationId}/responses/{responseId}/restart",
                        COORDINATION_ID,
                        tenantResponseId)
                    .with(authentication(staffAuthentication))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(1) + "]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customerLinkUrl").isString())
            .andExpect(jsonPath("$.linkExpiresAt").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String newLinkUrl = extractString(restartBody, "customerLinkUrl");
    String newToken = newLinkUrl.substring(newLinkUrl.lastIndexOf('/') + 1);
    mockMvc
        .perform(get("/api/public/visit-responses/{token}", newToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("WAITING"));

    mockMvc
        .perform(get("/api/public/visit-responses/{token}", oldToken))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PUBLIC_LINK_NOT_FOUND"));
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT result, submitted_at, reset_count FROM coordination_customer_response"
                    + " WHERE id = ?",
                tenantResponseId))
        .containsEntry("result", "WAITING")
        .containsEntry("submitted_at", null)
        .containsEntry("reset_count", 1);
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT candidate_time_id FROM customer_response_candidate WHERE response_id = ?",
                Long.class,
                tenantResponseId))
        .containsExactly(candidateTimeIds.get(1));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result FROM coordination_customer_response WHERE id = ?",
                String.class,
                buyerResponseId))
        .isEqualTo("WAITING");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("TENANT_CHECKING");
  }

  @Test
  void 제출완료_응답은_재시작할_수_없다() throws Exception {
    Long tenantResponseId = tenantResponseId(COORDINATION_ID);
    jdbcTemplate.update(
        "UPDATE coordination_customer_response SET result = 'AVAILABLE_SUBMITTED' WHERE id = ?",
        tenantResponseId);

    mockMvc
        .perform(
            post(
                    "/api/coordinations/{coordinationId}/responses/{responseId}/restart",
                    COORDINATION_ID,
                    tenantResponseId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESPONSE_NOT_RESTARTABLE"));
  }

  @Test
  void 구매자_재시작_후보는_세입자_승인_후보로_제한한다() throws Exception {
    Long tenantResponseId = tenantResponseId(COORDINATION_ID);
    jdbcTemplate.update(
        "UPDATE coordination_customer_response SET result = 'AVAILABLE_SUBMITTED' WHERE id = ?",
        tenantResponseId);
    offerCandidate(tenantResponseId, COORDINATION_ID, candidateTimeIds.get(0), true);
    offerCandidate(tenantResponseId, COORDINATION_ID, candidateTimeIds.get(1), false);
    Long buyerResponseId = insertBuyerResponse(COORDINATION_ID, "NONE_AVAILABLE");

    mockMvc
        .perform(
            post(
                    "/api/coordinations/{coordinationId}/responses/{responseId}/restart",
                    COORDINATION_ID,
                    buyerResponseId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(1) + "]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_ALLOWED"));
  }

  @Test
  void 재시작은_사무소_격리와_인증_Csrf를_강제한다() throws Exception {
    Long otherResponseId = tenantResponseId(OTHER_COORDINATION_ID);
    String body = "{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "]}";

    mockMvc
        .perform(
            post(
                    "/api/coordinations/{coordinationId}/responses/{responseId}/restart",
                    OTHER_COORDINATION_ID,
                    otherResponseId)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    mockMvc
        .perform(
            post(
                    "/api/coordinations/{coordinationId}/responses/{responseId}/restart",
                    COORDINATION_ID,
                    tenantResponseId(COORDINATION_ID))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post(
                    "/api/coordinations/{coordinationId}/responses/{responseId}/restart",
                    COORDINATION_ID,
                    tenantResponseId(COORDINATION_ID))
                .with(authentication(staffAuthentication))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void 목록과_상세는_사무소별_조율과_현재_응답_상태를_반환한다() throws Exception {
    Long tenantResponseId = tenantResponseId(COORDINATION_ID);
    offerCandidate(tenantResponseId, COORDINATION_ID, candidateTimeIds.get(0), false);
    offerCandidate(tenantResponseId, COORDINATION_ID, candidateTimeIds.get(1), false);
    issueLink(tenantResponseId, Instant.now().minus(Duration.ofDays(8)));
    jdbcTemplate.update(
        "UPDATE coordination_customer_response SET result = 'WAITING', submitted_at = NULL"
            + " WHERE id = ?",
        tenantResponseId);

    mockMvc
        .perform(get("/api/coordinations").with(authentication(staffAuthentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusCounts.tenantChecking").value(1))
        .andExpect(jsonPath("$.statusCounts.buyerDeliveryRequired").value(0))
        .andExpect(jsonPath("$.statusCounts.buyerChecking").value(0))
        .andExpect(jsonPath("$.statusCounts.finalConfirmationRequired").value(0))
        .andExpect(jsonPath("$.statusCounts.scheduleConfirmed").value(0))
        .andExpect(jsonPath("$.statusCounts.visitCompleted").value(0))
        .andExpect(jsonPath("$.coordinations.length()").value(1))
        .andExpect(jsonPath("$.coordinations[0].coordinationId").value(COORDINATION_ID))
        .andExpect(jsonPath("$.coordinations[0].propertyAddress").value("서울시 강남구 테헤란로 10"))
        .andExpect(jsonPath("$.coordinations[0].tenantName").value("김세입자"))
        .andExpect(jsonPath("$.coordinations[0].tenantResult").value("WAITING"))
        .andExpect(jsonPath("$.coordinations[0].buyerResults").isEmpty());

    mockMvc
        .perform(
            get("/api/coordinations/{coordinationId}", COORDINATION_ID)
                .with(authentication(staffAuthentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.property.propertyId").value(propertyId))
        .andExpect(jsonPath("$.candidateTimes.length()").value(2))
        .andExpect(jsonPath("$.tenantResponse.responseId").value(tenantResponseId))
        .andExpect(jsonPath("$.tenantResponse.result").value("EXPIRED"))
        .andExpect(jsonPath("$.tenantResponse.offeredCandidateIds.length()").value(2))
        .andExpect(jsonPath("$.tenantResponse.customerLinkUrl").isString())
        .andExpect(jsonPath("$.buyerResponses.length()").value(0));
  }

  @Test
  void 목록은_구매희망자_응답_결과를_배열로_반환한다() throws Exception {
    insertBuyerResponse(COORDINATION_ID, "NONE_AVAILABLE");
    insertBuyerResponse(COORDINATION_ID, "WAITING");

    mockMvc
        .perform(get("/api/coordinations").with(authentication(staffAuthentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coordinations[0].tenantResult").value("NONE_AVAILABLE"))
        .andExpect(jsonPath("$.coordinations[0].buyerResults.length()").value(2))
        .andExpect(
            jsonPath("$.coordinations[0].buyerResults")
                .value(org.hamcrest.Matchers.containsInAnyOrder("NONE_AVAILABLE", "WAITING")));
  }

  @Test
  void 조회는_다른_사무소_상세를_숨기고_미인증_접근을_거부한다() throws Exception {
    mockMvc
        .perform(
            get("/api/coordinations/{coordinationId}", OTHER_COORDINATION_ID)
                .with(authentication(staffAuthentication)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    mockMvc.perform(get("/api/coordinations")).andExpect(status().isUnauthorized());
  }

  @Test
  void 홈은_서울_오늘_확정건과_완료를_제외한_진행중_조율을_집계한다() throws Exception {
    Instant seoulToday = LocalDate.now(SEOUL).atStartOfDay(SEOUL).plusMinutes(30).toInstant();
    insertCoordination(
        9710L, OFFICE_ID, propertyId, MEMBERSHIP_ID, "BUYER_DELIVERY_REQUIRED", null);
    insertCoordination(9711L, OFFICE_ID, propertyId, MEMBERSHIP_ID, "BUYER_CHECKING", null);
    insertCoordination(
        9712L, OFFICE_ID, propertyId, MEMBERSHIP_ID, "FINAL_CONFIRMATION_REQUIRED", null);
    insertCoordination(
        9713L, OFFICE_ID, propertyId, MEMBERSHIP_ID, "SCHEDULE_CONFIRMED", seoulToday);
    insertCoordination(9714L, OFFICE_ID, propertyId, MEMBERSHIP_ID, "VISIT_COMPLETED", seoulToday);
    insertCoordination(
        9715L,
        OTHER_OFFICE_ID,
        otherPropertyId,
        OTHER_MEMBERSHIP_ID,
        "SCHEDULE_CONFIRMED",
        seoulToday);

    mockMvc
        .perform(get("/api/home").with(authentication(staffAuthentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.todayVisitCount").value(1))
        .andExpect(jsonPath("$.inProgressCoordinationCount").value(5))
        .andExpect(jsonPath("$.contractExpiringD90Count").value(0))
        .andExpect(jsonPath("$.coordinationStatusCounts.tenantChecking").value(1))
        .andExpect(jsonPath("$.coordinationStatusCounts.buyerDeliveryRequired").value(1))
        .andExpect(jsonPath("$.coordinationStatusCounts.buyerChecking").value(1))
        .andExpect(jsonPath("$.coordinationStatusCounts.finalConfirmationRequired").value(1))
        .andExpect(jsonPath("$.coordinationStatusCounts.scheduleConfirmed").value(1))
        .andExpect(jsonPath("$.coordinationStatusCounts.visitCompleted").value(1));
  }

  private void insertOffice(Long officeId, Long businessRegistrationNumber) {
    jdbcTemplate.update(
        "INSERT INTO office (id, name, representative_name, business_registration_number,"
            + " real_estate_license_number, phone, address, created_at)"
            + " VALUES (?, '사이로 사무소', '김대표', ?, ?, '02-1234-5678', '서울시 강남구', now())",
        officeId,
        businessRegistrationNumber,
        "1234567890" + officeId);
  }

  private void insertUser(Long userId, String name) {
    jdbcTemplate.update(
        "INSERT INTO app_user (id, kakao_provider_key, name, created_at) VALUES (?, ?, ?, now())",
        userId,
        "kakao-overview-" + userId,
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

  private Long insertProperty(
      Long officeId, String address, String addressDetail, String propertyName) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO property (office_id, address, address_detail, property_name, deal_type, created_at)"
            + " VALUES (?, ?, ?, ?, 'JEONSE', now()) RETURNING id",
        Long.class,
        officeId,
        address,
        addressDetail,
        propertyName);
  }

  private void insertCoordination(
      Long coordinationId,
      Long officeId,
      Long targetPropertyId,
      Long membershipId,
      String coordinationStatus,
      Instant scheduledAt) {
    if (scheduledAt == null) {
      jdbcTemplate.update(
          "INSERT INTO coordination (id, office_id, property_id, status, created_by_membership_id,"
              + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())",
          coordinationId,
          officeId,
          targetPropertyId,
          coordinationStatus,
          membershipId);
      return;
    }
    jdbcTemplate.update(
        "INSERT INTO coordination (id, office_id, property_id, status, created_by_membership_id,"
            + " scheduled_at, confirmed_at, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, now(), now(), now())",
        coordinationId,
        officeId,
        targetPropertyId,
        coordinationStatus,
        membershipId,
        Timestamp.from(scheduledAt));
  }

  private Long insertCandidateTime(Long coordinationId, Instant startsAt) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_candidate_time (coordination_id, starts_at)"
            + " VALUES (?, ?) RETURNING id",
        Long.class,
        coordinationId,
        Timestamp.from(startsAt));
  }

  private Long insertTenantResponse(Long coordinationId, String result, Instant submittedAt) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_customer_response"
            + " (coordination_id, role, customer_name, customer_phone, result, submitted_at,"
            + " reset_count, created_at) VALUES (?, 'TENANT', '김세입자', '010-1111-2222', ?, ?, 0, now())"
            + " RETURNING id",
        Long.class,
        coordinationId,
        result,
        Timestamp.from(submittedAt));
  }

  private Long insertBuyerResponse(Long coordinationId, String result) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_customer_response"
            + " (coordination_id, role, result, reset_count, created_at)"
            + " VALUES (?, 'BUYER', ?, 0, now()) RETURNING id",
        Long.class,
        coordinationId,
        result);
  }

  private Long tenantResponseId(Long coordinationId) {
    return jdbcTemplate.queryForObject(
        "SELECT id FROM coordination_customer_response"
            + " WHERE coordination_id = ? AND role = 'TENANT'",
        Long.class,
        coordinationId);
  }

  private void offerCandidate(
      Long responseId, Long coordinationId, Long candidateTimeId, boolean selected) {
    jdbcTemplate.update(
        "INSERT INTO customer_response_candidate (response_id, coordination_id, candidate_time_id,"
            + " is_selected) VALUES (?, ?, ?, ?)",
        responseId,
        coordinationId,
        candidateTimeId,
        selected);
  }

  private IssuedTestLink issueLink(Long responseId, Instant issuedAt) {
    CustomerResponseLink link =
        CustomerResponseLink.issueWithProvisionalHash(
            responseId, tokenGenerator.hash("provisional-" + responseId), issuedAt);
    linkRepository.saveAndFlush(link);
    String token = tokenGenerator.generate(link.getId(), link.getIssuedAt());
    link.assignTokenHash(tokenGenerator.hash(token));
    linkRepository.saveAndFlush(link);
    return new IssuedTestLink(token);
  }

  private String extractString(String body, String field) {
    Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException(field + " not found in response: " + body);
    }
    return matcher.group(1);
  }

  private record IssuedTestLink(String token) {}
}
