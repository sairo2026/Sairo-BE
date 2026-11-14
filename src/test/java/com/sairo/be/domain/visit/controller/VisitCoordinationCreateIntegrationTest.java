package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class VisitCoordinationCreateIntegrationTest extends VisitIntegrationTestBase {

  @Test
  void 대표가_조율을_만들면_시간_수집_중_상태와_후보_순서와_이력이_저장된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long first = newProperty(cookie);
    long second = newProperty(cookie);

    JsonNode created =
        createCoordination(cookie, customer, null, today(), today().plusDays(10), second, first);

    assertThat(created.get("status").asString()).isEqualTo("TIME_COLLECTING");
    assertThat(created.get("officeId").longValue()).isEqualTo(owner.office().getId());
    assertThat(created.get("version").longValue()).isEqualTo(1L);
    assertThat(created.get("candidateProperties")).hasSize(2);
    assertThat(created.get("candidateProperties").get(0).get("propertyId").longValue())
        .isEqualTo(second);
    assertThat(created.get("candidateProperties").get(0).get("sortOrder").intValue()).isEqualTo(1);
    assertThat(created.get("candidateProperties").get(1).get("sortOrder").intValue()).isEqualTo(2);
    long id = created.get("coordinationId").longValue();
    Map<String, Object> history =
        jdbcTemplate.queryForMap(
            "SELECT from_status, to_status, source, actor_membership_id FROM"
                + " visit_coordination_status_history WHERE coordination_id = ?",
            id);
    assertThat(history.get("from_status")).isNull();
    assertThat(history.get("to_status")).isEqualTo("TIME_COLLECTING");
    assertThat(history.get("source")).isEqualTo("STAFF");
    assertThat(((Number) history.get("actor_membership_id")).longValue())
        .isEqualTo(owner.membership().getId());
    Long manager =
        jdbcTemplate.queryForObject(
            "SELECT manager_membership_id FROM visit_coordination WHERE id = ?", Long.class, id);
    assertThat(manager).isEqualTo(owner.membership().getId());
    Long propertyOffice =
        jdbcTemplate.queryForObject(
            "SELECT property_office_id FROM visit_candidate_property WHERE coordination_id = ?"
                + " LIMIT 1",
            Long.class,
            id);
    assertThat(propertyOffice).isEqualTo(owner.office().getId());
  }

  @Test
  void 후보_매물_스무_개까지는_만들어지고_스물한_개는_422다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      ids.add(newProperty(cookie));
    }

    mockMvc
        .perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today(), today().plusDays(3), ids.subList(0, 20)),
                cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.candidateProperties.length()").value(20));
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today(), today().plusDays(3), ids),
                cookie)),
        ErrorCode.CANDIDATE_LIMIT_EXCEEDED);
  }

  @Test
  void 방문_가능_기간이_오늘부터_삼십일을_넘으면_422이고_삼십일은_허용된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long property = newProperty(cookie);

    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today(), today().plusDays(31), List.of(property)),
                cookie)),
        ErrorCode.PERIOD_TOO_LONG);
    mockMvc
        .perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today(), today().plusDays(30), List.of(property)),
                cookie))
        .andExpect(status().isCreated());
  }

  @Test
  void 형식이_잘못된_요청은_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long property = newProperty(cookie);
    LocalDate today = today();

    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(
                    customer, null, today.minusDays(1), today.plusDays(2), List.of(property)),
                cookie)),
        "periodStartDate",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today.plusDays(3), today.plusDays(2), List.of(property)),
                cookie)),
        "periodEndDate",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today, today.plusDays(2), List.of(property, property)),
                cookie)),
        "candidatePropertyIds",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today, today.plusDays(2), List.of()),
                cookie)),
        "candidatePropertyIds",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                "{\"customerId\":"
                    + customer
                    + ",\"candidatePropertyIds\":["
                    + property
                    + "],\"periodStartDate\":\""
                    + today
                    + "\",\"periodEndDate\":\""
                    + today
                    + "\",\"extra\":1}",
                cookie)),
        "extra",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                "{\"candidatePropertyIds\":[" + property + "]}",
                cookie)),
        "customerId",
        "REQUIRED");
  }

  @Test
  void 보관된_고객은_409이고_다른_사무소_고객은_404다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var foreignOwner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long archived = insertCustomer(owner.office().getId(), owner.membership().getId(), "보관");
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", archived);
    long foreign =
        insertCustomer(foreignOwner.office().getId(), foreignOwner.membership().getId(), "타사");
    long property = newProperty(cookie);

    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(archived, null, today(), today().plusDays(2), List.of(property)),
                cookie)),
        ErrorCode.CUSTOMER_ARCHIVED);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(foreign, null, today(), today().plusDays(2), List.of(property)),
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 병합된_고객을_고르면_최종_고객으로_조율이_만들어진다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long primary = insertCustomer(owner.office().getId(), owner.membership().getId(), "최종");
    long merged = insertCustomer(owner.office().getId(), owner.membership().getId(), "병합됨");
    mergeRow(primary, merged, owner.membership().getId());
    long property = newProperty(cookie);

    JsonNode created =
        createCoordination(cookie, merged, null, today(), today().plusDays(2), property);

    Long customerId =
        jdbcTemplate.queryForObject(
            "SELECT customer_id FROM visit_coordination WHERE id = ?",
            Long.class,
            created.get("coordinationId").longValue());
    assertThat(customerId).isEqualTo(primary);
  }

  @Test
  void 다른_사무소_보관_존재하지_않는_매물은_422이고_조율은_저장되지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var foreignOwner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long own = newProperty(cookie);
    long foreign = newProperty(cookieOf(foreignOwner));
    long archived = newProperty(cookie);
    archive(archived);

    for (long candidate : new long[] {foreign, archived, 987_654L}) {
      assertError(
          mockMvc.perform(
              jsonPostAs(
                  "/api/visit-coordinations",
                  createBody(customer, null, today(), today().plusDays(2), List.of(own, candidate)),
                  cookie)),
          ErrorCode.CANDIDATE_NOT_ALLOWED);
    }
    Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM visit_coordination", Long.class);
    assertThat(count).isZero();
  }

  @Test
  void 담당자는_같은_사무소의_승인된_소속만_지정할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var foreignOwner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    addAgentToRoster(owner.office().getId());
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long property = newProperty(cookie);

    JsonNode assigned =
        createCoordination(
            cookie, customer, deputy.membership().getId(), today(), today().plusDays(2), property);
    Long manager =
        jdbcTemplate.queryForObject(
            "SELECT manager_membership_id FROM visit_coordination WHERE id = ?",
            Long.class,
            assigned.get("coordinationId").longValue());
    assertThat(manager).isEqualTo(deputy.membership().getId());
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(
                    customer,
                    foreignOwner.membership().getId(),
                    today(),
                    today().plusDays(2),
                    List.of(property)),
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 중개보조원은_본인을_담당자로만_만들_수_있고_한도_초과_중에는_잠긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    Cookie ownerCookie = cookieOf(owner);
    Cookie deputyCookie = cookieOf(deputy);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long property = newProperty(ownerCookie);

    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(customer, null, today(), today().plusDays(2), List.of(property)),
                deputyCookie)),
        ErrorCode.DEPUTY_VISIT_LOCKED);

    addAgentToRoster(owner.office().getId());
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/visit-coordinations",
                createBody(
                    customer,
                    owner.membership().getId(),
                    today(),
                    today().plusDays(2),
                    List.of(property)),
                deputyCookie)),
        ErrorCode.FORBIDDEN);
    JsonNode created =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/visit-coordinations",
                        createBody(customer, null, today(), today().plusDays(2), List.of(property)),
                        deputyCookie))
                .andExpect(status().isCreated())
                .andReturn());
    Long manager =
        jdbcTemplate.queryForObject(
            "SELECT manager_membership_id FROM visit_coordination WHERE id = ?",
            Long.class,
            created.get("coordinationId").longValue());
    assertThat(manager).isEqualTo(deputy.membership().getId());
  }

  @Test
  void 대표가_중개보조원을_담당자로_지정해도_한도_초과_중에는_잠긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    Cookie ownerCookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long property = newProperty(ownerCookie);
    String body =
        createBody(
            customer, deputy.membership().getId(), today(), today().plusDays(2), List.of(property));

    assertError(
        mockMvc.perform(jsonPostAs("/api/visit-coordinations", body, ownerCookie)),
        ErrorCode.DEPUTY_VISIT_LOCKED);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM visit_coordination", Long.class))
        .isZero();

    addAgentToRoster(owner.office().getId());
    mockMvc
        .perform(jsonPostAs("/api/visit-coordinations", body, ownerCookie))
        .andExpect(status().isCreated());
  }

  @Test
  void 로그인이_없으면_401이고_소속이_없는_회원은_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    String body = createBody(customer, null, today(), today().plusDays(2), List.of(1L));

    assertError(
        mockMvc.perform(jsonPostAs("/api/visit-coordinations", body, null)),
        ErrorCode.UNAUTHENTICATED);
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));
    assertError(
        mockMvc.perform(jsonPostAs("/api/visit-coordinations", body, plain)), ErrorCode.FORBIDDEN);
  }
}
