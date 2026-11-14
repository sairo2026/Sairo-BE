package com.sairo.be.domain.customer.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.RequestBuilder;

@IntegrationTest
class CustomerAccessMatrixIntegrationTest extends CustomerIntegrationTestBase {

  private record Ids(long customer, long property, long link, long interest, long condition) {}

  private Ids fixture(Cookie cookie, long membershipId, long officeId) throws Exception {
    long customer = createCustomer(cookie, "기준", "010-1111-2222", membershipId);
    long property = createProperty(cookie, "기준 매물", categoryIdOf("APARTMENT"));
    long link = linkOwnerOccupant(officeId, property, customer, "OWNER", "2024-01-01");
    Long interest =
        jdbcTemplate.queryForObject(
            "INSERT INTO customer_property_interest (office_id, customer_id, property_id, start_date)"
                + " VALUES (?, ?, ?, '2024-01-01') RETURNING id",
            Long.class,
            officeId,
            customer,
            property);
    Long condition =
        jdbcTemplate.queryForObject(
            "INSERT INTO customer_demand_condition (customer_id, deal_type) VALUES (?, 'SALE') RETURNING id",
            Long.class,
            customer);
    return new Ids(
        customer,
        property,
        link,
        interest == null ? -1 : interest,
        condition == null ? -1 : condition);
  }

  private Map<String, Function<Cookie, RequestBuilder>> endpoints(Ids ids) {
    Map<String, Function<Cookie, RequestBuilder>> map = new LinkedHashMap<>();
    map.put("GET /api/customers", c -> authedGet("/api/customers", c));
    map.put("POST /api/customers", c -> jsonPostAs("/api/customers", "{}", c));
    map.put(
        "GET /api/customers/duplicate-candidates",
        c ->
            authedGet("/api/customers/duplicate-candidates", c)
                .param("name", "가")
                .param("phone", "010"));
    map.put("GET /api/customers/{id}", c -> authedGet("/api/customers/" + ids.customer(), c));
    map.put(
        "PATCH /api/customers/{id}",
        c -> jsonPatchAs("/api/customers/" + ids.customer(), "{\"memo\":\"x\"}", c, 9));
    map.put(
        "POST /api/customers/{id}/restore",
        c -> jsonPostAs("/api/customers/" + ids.customer() + "/restore", "{}", c));
    map.put(
        "GET /api/customers/{id}/assistant-view",
        c -> authedGet("/api/customers/" + ids.customer() + "/assistant-view", c));
    map.put(
        "POST /api/properties/{id}/owner-occupants",
        c ->
            jsonPostAs(
                "/api/properties/" + ids.property() + "/owner-occupants",
                "{\"customerId\":"
                    + ids.customer()
                    + ",\"role\":\"OWNER\",\"startDate\":\"2024-01-01\"}",
                c));
    map.put(
        "PATCH /api/owner-occupants/{id}",
        c -> jsonPatchAs("/api/owner-occupants/" + ids.link(), "{\"endDate\":null}", c, 9));
    map.put(
        "POST /api/customers/{id}/property-interests",
        c ->
            jsonPostAs(
                "/api/customers/" + ids.customer() + "/property-interests",
                "{\"propertyId\":" + ids.property() + ",\"startDate\":\"2024-01-01\"}",
                c));
    map.put(
        "PATCH /api/customer-property-interests/{id}",
        c ->
            jsonPatchAs(
                "/api/customer-property-interests/" + ids.interest(), "{\"note\":null}", c, 9));
    map.put(
        "POST /api/customers/{id}/demand-conditions",
        c ->
            jsonPostAs(
                "/api/customers/" + ids.customer() + "/demand-conditions",
                "{\"dealType\":\"SALE\"}",
                c));
    map.put(
        "PATCH /api/demand-conditions/{id}",
        c -> jsonPatchAs("/api/demand-conditions/" + ids.condition(), "{\"rentMax\":1}", c, 9));
    map.put(
        "GET /api/demand-conditions/{id}/candidate-properties",
        c -> authedGet("/api/demand-conditions/" + ids.condition() + "/candidate-properties", c));
    map.put("DELETE /api/customers/{id}", c -> deleteAs("/api/customers/" + ids.customer(), c));
    return map;
  }

  @Test
  void 로그인이_없으면_모든_엔드포인트가_401이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Ids ids = fixture(cookieOf(owner), owner.membership().getId(), owner.office().getId());
    for (var entry : endpoints(ids).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(null)), ErrorCode.UNAUTHENTICATED);
    }
  }

  @Test
  void 소속이_없는_회원은_모든_엔드포인트가_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Ids ids = fixture(cookieOf(owner), owner.membership().getId(), owner.office().getId());
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));
    for (var entry : endpoints(ids).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(plain)), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 중개보조원은_모든_엔드포인트가_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Ids ids = fixture(cookieOf(owner), owner.membership().getId(), owner.office().getId());
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    for (var entry : endpoints(ids).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(deputy)), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 다른_사무소_구성원은_자원_경로에서_404를_받는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Ids ids = fixture(cookieOf(owner), owner.membership().getId(), owner.office().getId());
    Cookie foreign = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    for (var entry : endpoints(ids).entrySet()) {
      if (!entry.getKey().contains("{id}")) {
        continue;
      }
      mockMvc.perform(entry.getValue().apply(foreign)).andExpect(status().isNotFound());
    }
  }

  @Test
  void 대표와_소속공인중개사는_고객_엔드포인트를_호출할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Ids ids = fixture(cookieOf(owner), owner.membership().getId(), owner.office().getId());
    Cookie agent = cookieOf(staffIn(owner.office(), OfficeMembershipRole.AGENT));
    for (var entry : endpoints(ids).entrySet()) {
      String body =
          mockMvc
              .perform(entry.getValue().apply(agent))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).as(entry.getKey()).doesNotContain("\"UNAUTHENTICATED\"", "\"FORBIDDEN\"");
    }
  }
}
