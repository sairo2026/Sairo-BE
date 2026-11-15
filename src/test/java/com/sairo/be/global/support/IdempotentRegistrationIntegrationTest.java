package com.sairo.be.global.support;

import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class IdempotentRegistrationIntegrationTest extends CustomerIntegrationTestBase {

  private MockHttpServletRequestBuilder withKey(
      String url, String body, Cookie cookie, String key) {
    MockHttpServletRequestBuilder builder = jsonPostAs(url, body, cookie);
    return key == null ? builder : builder.header(IdempotentRequests.HEADER, key);
  }

  private long count(String table) {
    Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return count == null ? -1 : count;
  }

  private String propertyBody(String name) {
    return "{\"name\":\"" + name + "\",\"categoryId\":" + categoryIdOf("APARTMENT") + "}";
  }

  private JsonNode post(String url, String body, Cookie cookie, String key, int expected)
      throws Exception {
    ResultActions result = mockMvc.perform(withKey(url, body, cookie, key));
    return AuthFlowSupport.json(result.andExpect(status().is(expected)).andReturn());
  }

  @Test
  void 같은_키로_매물_등록을_두_번_보내면_한_건만_만들고_첫_응답을_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    JsonNode first = post("/api/properties", propertyBody("역삼 빌딩"), cookie, "key-1", 201);
    JsonNode second = post("/api/properties", propertyBody("역삼 빌딩"), cookie, "key-1", 201);

    assertThat(second.get("propertyId").longValue()).isEqualTo(first.get("propertyId").longValue());
    assertThat(second.get("version").longValue()).isEqualTo(first.get("version").longValue());
    assertThat(count("property")).isEqualTo(1);
    assertThat(count("entity_lifecycle_event")).isEqualTo(1);
    assertThat(count("idempotency_record")).isEqualTo(1);
  }

  @Test
  void 같은_키로_고객_등록을_두_번_보내면_한_건만_만든다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    String body = customerBody("김휴이", "01012345678", owner.membership().getId(), "");

    JsonNode first = post("/api/customers", body, cookie, "key-1", 201);
    JsonNode second = post("/api/customers", body, cookie, "key-1", 201);

    assertThat(second.get("customerId").longValue()).isEqualTo(first.get("customerId").longValue());
    assertThat(count("customer")).isEqualTo(1);
  }

  @Test
  void 키가_없거나_다른_키면_각각_새로_만든다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    post("/api/properties", propertyBody("A"), cookie, null, 201);
    post("/api/properties", propertyBody("A"), cookie, null, 201);
    post("/api/properties", propertyBody("A"), cookie, "key-1", 201);
    post("/api/properties", propertyBody("A"), cookie, "key-2", 201);

    assertThat(count("property")).isEqualTo(4);
    assertThat(count("idempotency_record")).isEqualTo(2);
  }

  @Test
  void 같은_키에_다른_본문이면_400이고_새로_만들지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    post("/api/properties", propertyBody("A"), cookie, "key-1", 201);

    assertFieldError(
        mockMvc.perform(withKey("/api/properties", propertyBody("B"), cookie, "key-1")),
        IdempotentRequests.HEADER,
        "NOT_ALLOWED");

    assertThat(count("property")).isEqualTo(1);
  }

  @Test
  void 다른_구성원이_같은_키를_써도_서로_영향을_주지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);

    post("/api/properties", propertyBody("A"), cookieOf(owner), "key-1", 201);
    post("/api/properties", propertyBody("A"), cookieOf(other), "key-1", 201);

    assertThat(count("property")).isEqualTo(2);
  }

  @Test
  void 형식이_올바르지_않은_키는_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    assertFieldError(
        mockMvc.perform(withKey("/api/properties", propertyBody("A"), cookie, "공백 있는 키")),
        IdempotentRequests.HEADER,
        "INVALID_FORMAT");

    assertThat(count("property")).isZero();
  }

  @Test
  void 등록이_실패하면_키를_저장하지_않아_같은_키로_다시_보낼_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    mockMvc
        .perform(
            withKey("/api/properties", "{\"name\":\"\",\"categoryId\":1}", cookie, "key-1")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());

    post("/api/properties", propertyBody("A"), cookie, "key-1", 201);

    assertThat(count("property")).isEqualTo(1);
    assertThat(count("idempotency_record")).isEqualTo(1);
  }

  @Test
  void 만료된_기록은_다시_처리한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    post("/api/properties", propertyBody("A"), cookie, "key-1", 201);
    jdbcTemplate.update("UPDATE idempotency_record SET created_at = now() - interval '25 hours'");

    post("/api/properties", propertyBody("A"), cookie, "key-1", 201);

    assertThat(count("property")).isEqualTo(2);
    assertThat(count("idempotency_record")).isEqualTo(1);
  }
}
