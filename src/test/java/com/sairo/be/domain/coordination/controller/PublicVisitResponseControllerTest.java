package com.sairo.be.domain.coordination.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.TestcontainersConfiguration;
import com.sairo.be.domain.coordination.service.PublicLinkTokenGenerator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
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
class PublicVisitResponseControllerTest {

  private static final Long OFFICE_ID = 9901L;
  private static final Long USER_ID = 9902L;
  private static final Long MEMBERSHIP_ID = 9903L;
  private static final Long COORDINATION_ID = 9904L;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PublicLinkTokenGenerator tokenGenerator;

  private Long propertyId;
  private List<Long> candidateTimeIds;

  @BeforeEach
  void seed() {
    clearData();

    jdbcTemplate.update(
        "INSERT INTO office (id, name, representative_name, business_registration_number,"
            + " real_estate_license_number, phone, address, created_at)"
            + " VALUES (?, '사이로 데모 사무소', '김대표', ?, '99887766554', '02-1234-5678',"
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
    propertyId =
        jdbcTemplate.queryForObject(
            "INSERT INTO property (office_id, address, deal_type, created_at)"
                + " VALUES (?, '서울시 강남구 테헤란로 1', 'JEONSE', now()) RETURNING id",
            Long.class,
            OFFICE_ID);
    jdbcTemplate.update(
        "INSERT INTO coordination (id, office_id, property_id, status, created_by_membership_id,"
            + " created_at, updated_at) VALUES (?, ?, ?, 'TENANT_CHECKING', ?, now(), now())",
        COORDINATION_ID,
        OFFICE_ID,
        propertyId,
        MEMBERSHIP_ID);

    Instant base = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
    candidateTimeIds =
        List.of(
            insertCandidateTime(base),
            insertCandidateTime(base.plus(Duration.ofHours(1))),
            insertCandidateTime(base.plus(Duration.ofHours(2))));
  }

  @AfterEach
  void clearData() {
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
  }

  private Long insertCandidateTime(Instant startsAt) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_candidate_time (coordination_id, starts_at)"
            + " VALUES (?, ?) RETURNING id",
        Long.class,
        COORDINATION_ID,
        Timestamp.from(startsAt));
  }

  private Long insertTenantResponse(String result) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_customer_response"
            + " (coordination_id, role, customer_name, customer_phone, result, reset_count, created_at)"
            + " VALUES (?, 'TENANT', '김세입', '010-1111-2222', ?, 0, now()) RETURNING id",
        Long.class,
        COORDINATION_ID,
        result);
  }

  private Long insertBuyerResponse(String result) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO coordination_customer_response"
            + " (coordination_id, role, result, reset_count, created_at)"
            + " VALUES (?, 'BUYER', ?, 0, now()) RETURNING id",
        Long.class,
        COORDINATION_ID,
        result);
  }

  private void offerCandidateToBuyer(Long responseId, Long candidateTimeId) {
    jdbcTemplate.update(
        "INSERT INTO customer_response_candidate (response_id, coordination_id, candidate_time_id,"
            + " is_selected) VALUES (?, ?, ?, false)",
        responseId,
        COORDINATION_ID,
        candidateTimeId);
  }

  private String issueLink(Long responseId, Instant issuedAt, Instant expiresAt) {
    String provisionalHash = tokenGenerator.hash(UUID.randomUUID().toString());
    Long linkId =
        jdbcTemplate.queryForObject(
            "INSERT INTO customer_response_link (response_id, token_hash, issued_at, expires_at)"
                + " VALUES (?, ?, ?, ?) RETURNING id",
            Long.class,
            responseId,
            provisionalHash,
            Timestamp.from(issuedAt),
            Timestamp.from(expiresAt));
    String token = tokenGenerator.generate(linkId, issuedAt);
    jdbcTemplate.update(
        "UPDATE customer_response_link SET token_hash = ? WHERE id = ?",
        tokenGenerator.hash(token),
        linkId);
    return token;
  }

  private String issueActiveTenantLink(String result) {
    Long responseId = insertTenantResponse(result);
    return issueLink(responseId, Instant.now(), Instant.now().plus(Duration.ofDays(7)));
  }

  @Test
  void 유효한_세입자_토큰으로_조회하면_매물정보와_전체후보가_반환된다() throws Exception {
    String token = issueActiveTenantLink("WAITING");

    mockMvc
        .perform(get("/api/public/visit-responses/{token}", token))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(jsonPath("$.officeName").value("사이로 데모 사무소"))
        .andExpect(jsonPath("$.propertySummary.address").value("서울시 강남구 테헤란로 1"))
        .andExpect(jsonPath("$.propertySummary.dealType").value("JEONSE"))
        .andExpect(jsonPath("$.role").value("TENANT"))
        .andExpect(jsonPath("$.result").value("WAITING"))
        .andExpect(jsonPath("$.candidateTimes.length()").value(3))
        .andExpect(jsonPath("$.selectedCandidateIds.length()").value(0))
        .andExpect(jsonPath("$.scheduledAt").doesNotExist())
        .andExpect(jsonPath("$.expiresAt").exists());
  }

  @Test
  void 유효하지_않은_토큰이면_404를_반환한다() throws Exception {
    mockMvc
        .perform(get("/api/public/visit-responses/{token}", "no-such-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PUBLIC_LINK_NOT_FOUND"));
  }

  @Test
  void 만료된_링크면_404를_반환한다() throws Exception {
    Long responseId = insertTenantResponse("WAITING");
    Instant issuedAt = Instant.now().minus(Duration.ofDays(8));
    String token = issueLink(responseId, issuedAt, issuedAt.plus(Duration.ofDays(7)));

    mockMvc
        .perform(get("/api/public/visit-responses/{token}", token))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PUBLIC_LINK_NOT_FOUND"));
  }

  @Test
  void 후보_2개를_처음_제출하면_확정제출로_전이하고_조율현황도_함께_전이한다() throws Exception {
    String token = issueActiveTenantLink("WAITING");
    String body =
        "{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "," + candidateTimeIds.get(1) + "]}";

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("AVAILABLE_SUBMITTED"))
        .andExpect(jsonPath("$.selectedCandidateIds.length()").value(2));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("BUYER_DELIVERY_REQUIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?"
                    + " AND from_status = 'TENANT_CHECKING' AND to_status = 'BUYER_DELIVERY_REQUIRED'"
                    + " AND source = 'TENANT'",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }

  @Test
  void 제공되지_않은_후보를_제출하면_422를_반환한다() throws Exception {
    String token = issueActiveTenantLink("WAITING");

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[999999]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_ALLOWED"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("TENANT_CHECKING");
  }

  @Test
  void 재제출하면_선택이_최신_제출로_교체되고_조율현황은_다시_전이하지_않는다() throws Exception {
    String token = issueActiveTenantLink("WAITING");
    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "]}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(2) + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selectedCandidateIds.length()").value(1))
        .andExpect(jsonPath("$.selectedCandidateIds[0]").value(candidateTimeIds.get(2)));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM coordination_status_history WHERE coordination_id = ?",
                Integer.class,
                COORDINATION_ID))
        .isEqualTo(1);
  }

  @Test
  void 대기중_상태에서_가능한_시간_없음을_제출하면_전체불가로_전이하고_조율현황은_바뀌지_않는다() throws Exception {
    String token = issueActiveTenantLink("WAITING");

    mockMvc
        .perform(post("/api/public/visit-responses/{token}/no-availability", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("NONE_AVAILABLE"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("TENANT_CHECKING");
  }

  @Test
  void 확정된_응답에_제출을_시도하면_409를_반환한다() throws Exception {
    String token = issueActiveTenantLink("CONFIRMED");

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
  }

  @Test
  void 확정된_세입자_링크는_result와_무관하게_방문일시를_보여준다() throws Exception {
    Long responseId = insertTenantResponse("AVAILABLE_SUBMITTED");
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'SCHEDULE_CONFIRMED', scheduled_at = ?, confirmed_at = now()"
            + " WHERE id = ?",
        Timestamp.from(Instant.now().plus(Duration.ofDays(2))),
        COORDINATION_ID);
    String token = issueLink(responseId, Instant.now(), Instant.now().plus(Duration.ofDays(7)));

    mockMvc
        .perform(get("/api/public/visit-responses/{token}", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("AVAILABLE_SUBMITTED"))
        .andExpect(jsonPath("$.scheduledAt").exists());
  }

  @Test
  void 선택되지_않은_구매자는_확정되어도_방문일시를_보지_못한다() throws Exception {
    Long responseId = insertBuyerResponse("NOT_SELECTED");
    offerCandidateToBuyer(responseId, candidateTimeIds.get(0));
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'SCHEDULE_CONFIRMED',"
            + " scheduled_at = now() + interval '2 day', confirmed_at = now() WHERE id = ?",
        COORDINATION_ID);
    String token = issueLink(responseId, Instant.now(), Instant.now().plus(Duration.ofDays(7)));

    mockMvc
        .perform(get("/api/public/visit-responses/{token}", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("NOT_SELECTED"))
        .andExpect(jsonPath("$.scheduledAt").doesNotExist());
  }

  @Test
  void 구매자는_세입자가_승인한_후보를_벗어나_제출하면_422를_반환한다() throws Exception {
    Long responseId = insertBuyerResponse("WAITING");
    offerCandidateToBuyer(responseId, candidateTimeIds.get(0));
    String token = issueLink(responseId, Instant.now(), Instant.now().plus(Duration.ofDays(7)));

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(1) + "]}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_ALLOWED"));
  }

  @Test
  void 구매자가_첫_제출을_하면_최종확정필요로_전이한다() throws Exception {
    jdbcTemplate.update(
        "UPDATE coordination SET status = 'BUYER_CHECKING' WHERE id = ?", COORDINATION_ID);
    Long responseId = insertBuyerResponse("WAITING");
    offerCandidateToBuyer(responseId, candidateTimeIds.get(0));
    String token = issueLink(responseId, Instant.now(), Instant.now().plus(Duration.ofDays(7)));

    mockMvc
        .perform(
            post("/api/public/visit-responses/{token}/available-times", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("AVAILABLE_SUBMITTED"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID))
        .isEqualTo("FINAL_CONFIRMATION_REQUIRED");
  }

  @Test
  void CSRF_토큰_없이도_공개_링크_제출이_허용된다() throws Exception {
    String token = issueActiveTenantLink("WAITING");

    mockMvc
        .perform(post("/api/public/visit-responses/{token}/no-availability", token))
        .andExpect(status().isOk());
  }

  @Test
  void 가능시간과_전체불가를_동시에_제출하면_하나만_반영된다() throws Exception {
    String token = issueActiveTenantLink("WAITING");
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Integer> available =
          executor.submit(
              () -> {
                start.await();
                return mockMvc
                    .perform(
                        post("/api/public/visit-responses/{token}/available-times", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateTimeIds\":[" + candidateTimeIds.get(0) + "]}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      Future<Integer> none =
          executor.submit(
              () -> {
                start.await();
                return mockMvc
                    .perform(post("/api/public/visit-responses/{token}/no-availability", token))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });

      start.countDown();
      assertThat(List.of(available.get(), none.get())).containsExactlyInAnyOrder(200, 409);
    }

    String result =
        jdbcTemplate.queryForObject(
            "SELECT result FROM coordination_customer_response WHERE coordination_id = ?",
            String.class,
            COORDINATION_ID);
    String status =
        jdbcTemplate.queryForObject(
            "SELECT status FROM coordination WHERE id = ?", String.class, COORDINATION_ID);
    if ("AVAILABLE_SUBMITTED".equals(result)) {
      assertThat(status).isEqualTo("BUYER_DELIVERY_REQUIRED");
    } else {
      assertThat(result).isEqualTo("NONE_AVAILABLE");
      assertThat(status).isEqualTo("TENANT_CHECKING");
    }
  }
}
