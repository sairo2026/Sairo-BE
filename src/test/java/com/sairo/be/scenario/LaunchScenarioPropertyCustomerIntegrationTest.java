package com.sairo.be.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** 개발플로우 4장 시나리오 ②·③을 실제 엔드포인트로 잇는 출시 검증 시험이다. */
@IntegrationTest
class LaunchScenarioPropertyCustomerIntegrationTest extends CustomerIntegrationTestBase {

  private JsonNode postCreated(String url, String body, Cookie cookie) throws Exception {
    return AuthFlowSupport.json(
        mockMvc.perform(jsonPostAs(url, body, cookie)).andExpect(status().isCreated()).andReturn());
  }

  private long newAddressedProperty(Cookie cookie, String name, String address) throws Exception {
    return postCreated(
            "/api/properties",
            "{\"name\":\""
                + name
                + "\",\"categoryId\":"
                + categoryIdOf("OFFICETEL")
                + ",\"address\":\""
                + address
                + "\"}",
            cookie)
        .get("propertyId")
        .longValue();
  }

  private void offer(Cookie cookie, long propertyId, String body) throws Exception {
    mockMvc
        .perform(jsonPostAs("/api/properties/" + propertyId + "/offers", body, cookie))
        .andExpect(status().isCreated());
  }

  @Test
  void 시나리오2_매물을_등록하고_커스텀_카테고리와_필드를_더해_값을_입력한다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);

    // 1. 기본 카테고리로 매물 임시 등록
    JsonNode property =
        postCreated(
            "/api/properties",
            "{\"name\":\"합정 타운하우스\",\"categoryId\":" + categoryIdOf("APARTMENT") + "}",
            cookie);
    long propertyId = property.get("propertyId").longValue();
    assertThat(property.get("registrationStage").asString()).isEqualTo("DRAFT");
    assertThat(property.get("version").longValue()).isEqualTo(1);

    // 2. 사무소 커스텀 카테고리와 두 필드 추가
    long categoryId =
        postCreated("/api/property-categories", "{\"name\":\"타운하우스\"}", cookie)
            .get("categoryId")
            .longValue();
    long viewField =
        postCreated(
                "/api/property-categories/" + categoryId + "/fields",
                "{\"fieldName\":\"조망\",\"fieldType\":\"TEXT\"}",
                cookie)
            .get("fieldDefinitionId")
            .longValue();
    long gardenField =
        postCreated(
                "/api/property-categories/" + categoryId + "/fields",
                "{\"fieldName\":\"정원 면적\",\"fieldType\":\"NUMBER\"}",
                cookie)
            .get("fieldDefinitionId")
            .longValue();
    mockMvc
        .perform(authedGet("/api/property-categories", cookie))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.categoryId == " + categoryId + ")].isSystem").value(false));
    mockMvc
        .perform(authedGet("/api/property-categories/" + categoryId + "/fields", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));

    // 3. 매물 카테고리를 커스텀 카테고리로 바꾸고 이력을 남긴다
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + propertyId + "/category",
                "{\"categoryId\":" + categoryId + "}",
                cookie,
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categoryId").value(categoryId))
        .andExpect(jsonPath("$.categoryName").value("타운하우스"))
        .andExpect(jsonPath("$.version").value(2));
    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT previous_category_id, new_category_id FROM entity_lifecycle_event WHERE"
                + " property_id = ? AND event_type = 'CATEGORY_CHANGED'",
            propertyId);
    assertThat(event)
        .containsEntry("previous_category_id", categoryIdOf("APARTMENT"))
        .containsEntry("new_category_id", categoryId);

    // 4. 커스텀 필드 값 입력과 상세 조회
    mockMvc
        .perform(
            jsonPutAs(
                "/api/properties/" + propertyId + "/fields/" + viewField,
                "{\"value\":\"한강 조망\"}",
                cookie,
                2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value("한강 조망"))
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(
            jsonPutAs(
                "/api/properties/" + propertyId + "/fields/" + gardenField,
                "{\"value\":33.5}",
                cookie,
                3))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value(33.5))
        .andExpect(jsonPath("$.version").value(4));
    mockMvc
        .perform(authedGet("/api/properties/" + propertyId, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categoryId").value(categoryId))
        .andExpect(jsonPath("$.customFields.length()").value(2))
        .andExpect(
            jsonPath("$.customFields[?(@.fieldDefinitionId == " + viewField + ")].value")
                .value("한강 조망"))
        .andExpect(
            jsonPath("$.customFields[?(@.fieldDefinitionId == " + gardenField + ")].value")
                .value(33.5))
        .andExpect(jsonPath("$.version").value(4));

    // 5. 다른 사무소에는 커스텀 카테고리와 매물이 보이지 않는다
    Cookie other = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    mockMvc
        .perform(authedGet("/api/property-categories/" + categoryId + "/fields", other))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(authedGet("/api/properties/" + propertyId, other))
        .andExpect(status().isNotFound());
  }

  @Test
  void 시나리오3_고객을_등록하고_희망조건을_넣으면_조건에_맞는_후보_매물만_조회된다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long manager = owner.membership().getId();

    // 1. 매물 세 건과 거래 제안 등록(적합, 가격 초과, 지역 다름)
    long fit = newAddressedProperty(cookie, "적합", "서울 강남구 역삼로 1");
    long expensive = newAddressedProperty(cookie, "비쌈", "서울 강남구 역삼로 2");
    long elsewhere = newAddressedProperty(cookie, "타지역", "부산 해운대구 1");
    String monthly =
        "{\"dealType\":\"MONTHLY\",\"depositAmount\":20000000,\"monthlyRentAmount\":900000}";
    offer(cookie, fit, monthly);
    offer(
        cookie,
        expensive,
        "{\"dealType\":\"MONTHLY\",\"depositAmount\":90000000,\"monthlyRentAmount\":900000}");
    offer(cookie, elsewhere, monthly);

    // 2. 고객 등록(담당자·수집근거·목적·보유기한 필수)
    JsonNode customer =
        postCreated("/api/customers", customerBody("이수요", "010-5555-6666", manager, ""), cookie);
    long customerId = customer.get("customerId").longValue();
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT managing_membership_id, data_source, data_purpose, retention_until FROM"
                + " customer WHERE id = ?",
            customerId);
    assertThat(row.get("managing_membership_id")).isEqualTo(manager);
    assertThat(row.get("data_source")).isEqualTo("방문상담");
    assertThat(row.get("retention_until")).isNotNull();

    // 3. 희망조건 입력
    long conditionId =
        postCreated(
                "/api/customers/" + customerId + "/demand-conditions",
                "{\"dealType\":\"MONTHLY\",\"categoryId\":"
                    + categoryIdOf("OFFICETEL")
                    + ",\"depositMin\":10000000,\"depositMax\":30000000,"
                    + "\"regions\":[\"강남구\"]}",
                cookie)
            .get("conditionId")
            .longValue();
    mockMvc
        .perform(authedGet("/api/customers/" + customerId, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerId").value(customerId));

    // 4. 후보 매물 필터 조회
    mockMvc
        .perform(
            authedGet("/api/demand-conditions/" + conditionId + "/candidate-properties", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].propertyId").value(fit))
        .andExpect(jsonPath("$.items[0].depositAmount").value(20000000))
        .andExpect(jsonPath("$.items[0].monthlyRentAmount").value(900000));

    // 5. 조건을 바꾸면 결과가 따라 바뀐다(보증금 상한을 올리면 가격 초과 매물도 들어온다)
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/demand-conditions/" + conditionId,
                "{\"depositMax\":100000000}",
                cookie,
                customerVersion(customerId)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            authedGet("/api/demand-conditions/" + conditionId + "/candidate-properties", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));

    // 6. 다른 사무소에서는 고객과 조건이 404다
    Cookie other = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    mockMvc
        .perform(authedGet("/api/customers/" + customerId, other))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            authedGet("/api/demand-conditions/" + conditionId + "/candidate-properties", other))
        .andExpect(status().isNotFound());
  }
}
