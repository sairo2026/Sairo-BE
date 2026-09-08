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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CoordinationConfirmControllerTest {

  private static final Long OFFICE_ID = 9601L;
  private static final Long OTHER_OFFICE_ID = 9602L;
  private static final Long USER_ID = 9603L;
  private static final Long MEMBERSHIP_ID = 9604L;
  private static final Long OTHER_OFFICE_USER_ID = 9605L;
  private static final Long OTHER_OFFICE_MEMBERSHIP_ID = 9606L;
  private static final Long COORDINATION_ID = 9607L;
  private static final Long OTHER_OFFICE_COORDINATION_ID = 9608L;

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

    insertOffice(OFFICE_ID, 8601001L);
    insertOffice(OTHER_OFFICE_ID, 8601002L);
    insertUser(USER_ID, "박직원");
    insertUser(OTHER_OFFICE_USER_ID, "박직원2");
    insertMembership(MEMBERSHIP_ID, USER_ID, OFFICE_ID);
    insertMembership(OTHER_OFFICE_MEMBERSHIP_ID, OTHER_OFFICE_USER_ID, OTHER_OFFICE_ID);

    Long propertyId = insertProperty(OFFICE_ID, "서울시 강남구 테헤란로 1");
    Long otherOfficePropertyId = insertProperty(OTHER_OFFICE_ID, "서울시 서초구 반포대로 1");

    insertCoordination(
        COORDINATION_ID, OFFICE_ID, propertyId, MEMBERSHIP_ID, "FINAL_CONFIRMATION_REQUIRED");
    insertCoordination(
        OTHER_OFFICE_COORDINATION_ID,
        OTHER_OFFICE_ID,
        otherOfficePropertyId,
        OTHER_OFFICE_MEMBERSHIP_ID,
        "FINAL_CONFIRMATION_REQUIRED");

    Instant base = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    candidateTimeIds =
        List.of(
            insertCandidateTime(COORDINATION_ID, base),
            insertCandidateTime(COORDINATION_ID, base.plus(Duration.ofHours(1))));

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

  private Long insertBuyerResponse(Long coordinationId, String result) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_customer_response"
            + " (coordination_id, role, result, reset_count, created_at)"
            + " VALUES (?, 'BUYER', ?, 0, now()) RETURNING id",
        Long.class,
        coordinationId,
        result);
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

  private String confirmRequestBody(Long buyerResponseId, Long candidateTimeId) {
    return "{\"buyerResponseId\":"
        + buyerResponseId
        + ",\"candidateTimeId\":"
        + candidateTimeId
        + "}";
  }

  @Test
  void 선택된_구매자는_확정되고_나머지는_선택되지_않음으로_전이하며_조율현황이_확정완료된다() throws Exception {
    Long selectedBuyerId = insertBuyerResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");
    offerCandidate(selectedBuyerId, COORDINATION_ID, candidateTimeIds.get(0), true);
    Long otherBuyerId = insertBuyerResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");
    offerCandidate(otherBuyerId, COORDINATION_ID, candidateTimeIds.get(1), true);

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/confirm", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmRequestBody(selectedBuyerId, candidateTimeIds.get(0))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULE_CONFIRMED"))
        .andExpect(jsonPath("$.scheduledAt").exists())
        .andExpect(jsonPath("$.confirmedBuyerResponseId").value(selectedBuyerId));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("SCHEDULE_CONFIRMED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT confirmed_candidate_time_id FROM coordination WHERE id = ?",
                Long.class,
                COORDINATION_ID))
        .isEqualTo(candidateTimeIds.get(0));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT selected_buyer_response_id FROM coordination WHERE id = ?",
                Long.class,
                COORDINATION_ID))
        .isEqualTo(selectedBuyerId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result FROM coordination_customer_response WHERE id = ?",
                String.class,
                selectedBuyerId))
        .isEqualTo("CONFIRMED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT result FROM coordination_customer_response WHERE id = ?",
                String.class,
                otherBuyerId))
        .isEqualTo("NOT_SELECTED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?"
                    + " AND from_status = 'FINAL_CONFIRMATION_REQUIRED' AND to_status = 'SCHEDULE_CONFIRMED'"
                    + " AND source = 'STAFF'",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }

  @Test
  void 최종확정필요_상태가_아니면_409를_반환한다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'BUYER_CHECKING' WHERE id = ?", COORDINATION_ID);
    Long buyerId = insertBuyerResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");
    offerCandidate(buyerId, COORDINATION_ID, candidateTimeIds.get(0), true);

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/confirm", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmRequestBody(buyerId, candidateTimeIds.get(0))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 구매자가_제출완료_상태가_아니면_409를_반환한다() throws Exception {
    Long buyerId = insertBuyerResponse(COORDINATION_ID, "WAITING");

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/confirm", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmRequestBody(buyerId, candidateTimeIds.get(0))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 구매자가_선택하지_않은_후보면_422를_반환한다() throws Exception {
    Long buyerId = insertBuyerResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");
    offerCandidate(buyerId, COORDINATION_ID, candidateTimeIds.get(0), true);

    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/confirm", COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmRequestBody(buyerId, candidateTimeIds.get(1))))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_ALLOWED"));
  }

  @Test
  void 다른_사무소_조율_건이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/confirm", OTHER_OFFICE_COORDINATION_ID)
                .with(authentication(staffAuthentication))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmRequestBody(1L, candidateTimeIds.get(0))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void 인증되지_않은_요청은_401을_반환한다() throws Exception {
    mockMvc
        .perform(
            post("/api/coordinations/{coordinationId}/confirm", COORDINATION_ID)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmRequestBody(1L, candidateTimeIds.get(0))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 동시_최종확정_요청은_하나만_성공한다() throws Exception {
    Long buyerAId = insertBuyerResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");
    offerCandidate(buyerAId, COORDINATION_ID, candidateTimeIds.get(0), true);
    Long buyerBId = insertBuyerResponse(COORDINATION_ID, "AVAILABLE_SUBMITTED");
    offerCandidate(buyerBId, COORDINATION_ID, candidateTimeIds.get(1), true);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    Callable<Integer> requestA =
        () -> {
          ready.countDown();
          start.await();
          MvcResult result =
              mockMvc
                  .perform(
                      post("/api/coordinations/{coordinationId}/confirm", COORDINATION_ID)
                          .with(authentication(staffAuthentication))
                          .with(csrf())
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(confirmRequestBody(buyerAId, candidateTimeIds.get(0))))
                  .andReturn();
          return result.getResponse().getStatus();
        };
    Callable<Integer> requestB =
        () -> {
          ready.countDown();
          start.await();
          MvcResult result =
              mockMvc
                  .perform(
                      post("/api/coordinations/{coordinationId}/confirm", COORDINATION_ID)
                          .with(authentication(staffAuthentication))
                          .with(csrf())
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(confirmRequestBody(buyerBId, candidateTimeIds.get(1))))
                  .andReturn();
          return result.getResponse().getStatus();
        };

    Future<Integer> futureA = executor.submit(requestA);
    Future<Integer> futureB = executor.submit(requestB);
    ready.await();
    start.countDown();

    int statusA = futureA.get();
    int statusB = futureB.get();
    executor.shutdown();

    List<Integer> statuses = List.of(statusA, statusB);
    assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("SCHEDULE_CONFIRMED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_customer_response WHERE coordination_id = ?"
                    + " AND result = 'CONFIRMED'",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }
}
