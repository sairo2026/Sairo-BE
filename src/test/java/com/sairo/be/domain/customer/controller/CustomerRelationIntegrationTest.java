package com.sairo.be.domain.customer.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class CustomerRelationIntegrationTest extends CustomerIntegrationTestBase {

  private long linkOf(Cookie cookie, long propertyId, long customerId, String role)
      throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/properties/" + propertyId + "/owner-occupants",
                        "{\"customerId\":"
                            + customerId
                            + ",\"role\":\""
                            + role
                            + "\",\"startDate\":\"2024-01-01\"}",
                        cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("linkId")
        .longValue();
  }

  @Test
  void 소유자를_연결하면_고객_버전이_오르고_확인_담당자가_기본으로_요청자다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long customer = createCustomer(cookie, "소유자", "010-1111-2222", m);
    long property = createProperty(cookie, "매물", categoryIdOf("APARTMENT"));
    mockMvc
        .perform(
            jsonPostAs(
                "/api/properties/" + property + "/owner-occupants",
                "{\"customerId\":" + customer + ",\"role\":\"OWNER\",\"startDate\":\"2024-01-01\"}",
                cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("OWNER"))
        .andExpect(jsonPath("$.customerId").value(customer))
        .andExpect(jsonPath("$.confirmedByMembershipId").value(m))
        .andExpect(jsonPath("$.confirmedAt").isString())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(customerVersion(customer)).isEqualTo(2);
    mockMvc
        .perform(authedGet("/api/properties/" + property, cookie))
        .andExpect(jsonPath("$.ownerOccupants[0].customerName").value("소유자"));
  }

  @Test
  void 병합된_고객을_연결하면_최종_고객에_연결하고_보관_고객과_보관_매물은_거절한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "남는", null, m);
    long b = createCustomer(cookie, "합쳐진", null, m);
    mergeRow(a, b, m);
    long property = createProperty(cookie, "매물", categoryIdOf("APARTMENT"));
    mockMvc
        .perform(
            jsonPostAs(
                "/api/properties/" + property + "/owner-occupants",
                "{\"customerId\":" + b + ",\"role\":\"OCCUPANT\",\"startDate\":\"2024-01-01\"}",
                cookie))
        .andExpect(jsonPath("$.customerId").value(a));
    long archivedCustomer = createCustomer(cookie, "보관", null, m);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", archivedCustomer);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties/" + property + "/owner-occupants",
                "{\"customerId\":"
                    + archivedCustomer
                    + ",\"role\":\"OWNER\",\"startDate\":\"2024-01-01\"}",
                cookie)),
        ErrorCode.CUSTOMER_ARCHIVED);
    archive(property);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties/" + property + "/owner-occupants",
                "{\"customerId\":" + a + ",\"role\":\"OWNER\",\"startDate\":\"2024-01-01\"}",
                cookie)),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 소유_점유_연결의_잘못된_요청은_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long customer = createCustomer(cookie, "검증", null, m);
    long property = createProperty(cookie, "매물", categoryIdOf("APARTMENT"));
    String url = "/api/properties/" + property + "/owner-occupants";
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, "{\"role\":\"OWNER\",\"startDate\":\"2024-01-01\"}", cookie)),
        "customerId",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"customerId\":"
                    + customer
                    + ",\"role\":\"BROKER\",\"startDate\":\"2024-01-01\"}",
                cookie)),
        "role",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, "{\"customerId\":" + customer + ",\"role\":\"OWNER\"}", cookie)),
        "startDate",
        "REQUIRED");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"customerId\":99999,\"role\":\"OWNER\",\"startDate\":\"2024-01-01\"}",
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"customerId\":" + customer + ",\"role\":\"OWNER\",\"startDate\":\"2024-01-01\"}",
                cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"customerId\":"
                    + customer
                    + ",\"role\":\"OWNER\",\"startDate\":\"2024-01-01\",\"confirmedByMembershipId\":"
                    + deputy.membership().getId()
                    + "}",
                cookie)),
        "confirmedByMembershipId",
        "NOT_ALLOWED");
  }

  @Test
  void 소유_점유_종료일은_고객_버전으로_수정하고_시작일_앞이면_INVALID_RANGE이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long customer = createCustomer(cookie, "종료", null, m);
    long property = createProperty(cookie, "매물", categoryIdOf("APARTMENT"));
    long link = linkOf(cookie, property, customer, "OWNER");
    String url = "/api/owner-occupants/" + link;
    mockMvc
        .perform(jsonPatchAs(url, "{\"endDate\":\"2026-12-31\"}", cookie, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endDate").value("2026-12-31"))
        .andExpect(jsonPath("$.version").value(3));
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"endDate\":\"2023-12-31\"}", cookie, 3)),
        ErrorCode.INVALID_RANGE);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"endDate\":\"2026-12-31\"}", cookie, 2)),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"endDate\":\"2026-12-31\"}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertFieldError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, 3)), "endDate", "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"role\":\"OCCUPANT\"}", cookie, 3)),
        "role",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"endDate\":null}", cookieOf(other), 3)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPatchAs("/api/owner-occupants/99999", "{\"endDate\":null}", cookie, 3)),
        ErrorCode.RESOURCE_NOT_FOUND);
    mockMvc
        .perform(jsonPatchAs(url, "{\"endDate\":null}", cookie, 3))
        .andExpect(jsonPath("$.endDate").doesNotExist());
  }

  @Test
  void 병합된_고객의_연결_기록은_최종_고객의_버전으로_수정한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "남는", null, m);
    long b = createCustomer(cookie, "합쳐진", null, m);
    long property = createProperty(cookie, "매물", categoryIdOf("APARTMENT"));
    long link = linkOf(cookie, property, b, "OWNER");
    mergeRow(a, b, m);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/owner-occupants/" + link,
                "{\"endDate\":\"2025-01-01\"}",
                cookie,
                customerVersion(a)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerId").value(b));
    assertThat(customerVersion(a)).isEqualTo(2);
  }

  @Test
  void 관심_매물을_등록하고_종료일과_메모를_수정한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long customer = createCustomer(cookie, "관심", null, m);
    long property = createProperty(cookie, "관심 매물", categoryIdOf("APARTMENT"));
    long interest =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/customers/" + customer + "/property-interests",
                            "{\"propertyId\":"
                                + property
                                + ",\"startDate\":\"2026-09-05\",\"note\":\"역세권\"}",
                            cookie))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.note").value("역세권"))
                    .andExpect(jsonPath("$.version").value(2))
                    .andReturn())
            .get("interestId")
            .longValue();
    String url = "/api/customer-property-interests/" + interest;
    mockMvc
        .perform(jsonPatchAs(url, "{\"endDate\":\"2026-09-05\",\"note\":\"계약 완료\"}", cookie, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.endDate").value("2026-09-05"))
        .andExpect(jsonPath("$.note").value("계약 완료"))
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(jsonPatchAs(url, "{\"note\":null}", cookie, 3))
        .andExpect(jsonPath("$.note").doesNotExist())
        .andExpect(jsonPath("$.endDate").value("2026-09-05"));
    mockMvc
        .perform(authedGet("/api/customers/" + customer, cookie))
        .andExpect(jsonPath("$.relatedProperties[0].role").value("INTEREST"))
        .andExpect(jsonPath("$.relatedProperties[0].interestId").value(interest))
        .andExpect(jsonPath("$.currentOwnedOccupied.length()").value(0));
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"endDate\":\"2026-09-04\"}", cookie, 4)),
        ErrorCode.INVALID_RANGE);
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"startDate\":\"2026-01-01\"}", cookie, 4)),
        "startDate",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"note\":\"x\"}", cookie, 1)),
        ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void 관심_매물_등록의_잘못된_요청은_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long customer = createCustomer(cookie, "오류", null, m);
    long property = createProperty(cookie, "매물", categoryIdOf("APARTMENT"));
    long foreignProperty = createProperty(cookieOf(other), "타사", categoryIdOf("APARTMENT"));
    String url = "/api/customers/" + customer + "/property-interests";
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"propertyId\":"
                    + property
                    + ",\"startDate\":\"2026-09-05\",\"endDate\":\"2026-09-01\"}",
                cookie)),
        ErrorCode.INVALID_RANGE);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url,
                "{\"propertyId\":" + foreignProperty + ",\"startDate\":\"2026-09-05\"}",
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers/99999/property-interests",
                "{\"propertyId\":" + property + ",\"startDate\":\"2026-09-05\"}",
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, "{\"startDate\":\"2026-09-05\"}", cookie)),
        "propertyId",
        "REQUIRED");
    archive(property);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url, "{\"propertyId\":" + property + ",\"startDate\":\"2026-09-05\"}", cookie)),
        ErrorCode.PROPERTY_ARCHIVED);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", customer);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url, "{\"propertyId\":" + property + ",\"startDate\":\"2026-09-05\"}", cookie)),
        ErrorCode.CUSTOMER_ARCHIVED);
  }

  @Test
  void 희망조건을_등록하고_수정하며_지역과_범위를_검사한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long customer = createCustomer(cookie, "수요", null, m);
    long condition =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/customers/" + customer + "/demand-conditions",
                            "{\"dealType\":\"MONTHLY\",\"categoryId\":"
                                + categoryIdOf("OFFICETEL")
                                + ",\"depositMin\":10000000,\"depositMax\":30000000,\"rentMin\":500000,\"rentMax\":1000000,"
                                + "\"regions\":[\"서울 강남구\",\"서울 강남구\",\"서울 서초구\"]}",
                            cookie))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.regions.length()").value(2))
                    .andExpect(jsonPath("$.regions[0].regionName").value("서울 강남구"))
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
                    .andReturn())
            .get("conditionId")
            .longValue();
    String url = "/api/demand-conditions/" + condition;
    mockMvc
        .perform(jsonPatchAs(url, "{\"depositMax\":35000000,\"regions\":[\"서울 마포구\"]}", cookie, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.depositMax").value(35000000))
        .andExpect(jsonPath("$.depositMin").value(10000000))
        .andExpect(jsonPath("$.regions.length()").value(1))
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(jsonPatchAs(url, "{\"categoryId\":null,\"regions\":null}", cookie, 3))
        .andExpect(jsonPath("$.categoryId").doesNotExist())
        .andExpect(jsonPath("$.regions.length()").value(0));
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"depositMin\":99999999}", cookie, 4)),
        ErrorCode.INVALID_RANGE);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"areaMin\":50,\"areaMax\":10}", cookie, 4)),
        ErrorCode.INVALID_RANGE);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"rentMax\":1}", cookie, 1)),
        ErrorCode.VERSION_CONFLICT);
    assertError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, 4)), ErrorCode.INVALID_REQUEST);
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"dealType\":null}", cookie, 4)),
        "dealType",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"customerId\":3}", cookie, 4)),
        "customerId",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPatchAs("/api/demand-conditions/99999", "{\"rentMax\":1}", cookie, 4)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers/" + customer + "/demand-conditions", "{\"depositMin\":1}", cookie)),
        "dealType",
        "REQUIRED");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers/" + customer + "/demand-conditions",
                "{\"dealType\":\"SALE\",\"priceMin\":10,\"priceMax\":5}",
                cookie)),
        ErrorCode.INVALID_RANGE);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers/" + customer + "/demand-conditions",
                "{\"dealType\":\"SALE\",\"categoryId\":99999}",
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 희망조건_후보_매물은_카테고리_거래유형_가격_지역으로_단순_필터링한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long customer = createCustomer(cookie, "수요", null, m);
    long fit =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/properties",
                            "{\"name\":\"적합\",\"categoryId\":"
                                + categoryIdOf("OFFICETEL")
                                + ",\"address\":\"서울 강남구 역삼로 1\"}",
                            cookie))
                    .andReturn())
            .get("propertyId")
            .longValue();
    long wrongPrice = createProperty(cookie, "비쌈", categoryIdOf("OFFICETEL"));
    long wrongRegion =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/properties",
                            "{\"name\":\"타지역\",\"categoryId\":"
                                + categoryIdOf("OFFICETEL")
                                + ",\"address\":\"부산 해운대구\"}",
                            cookie))
                    .andReturn())
            .get("propertyId")
            .longValue();
    insertOffer(fit, "MONTHLY", "AVAILABLE", null, 20_000_000L, 900_000L);
    insertOffer(wrongPrice, "MONTHLY", "AVAILABLE", null, 90_000_000L, 900_000L);
    insertOffer(wrongRegion, "MONTHLY", "AVAILABLE", null, 20_000_000L, 900_000L);
    long condition =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/customers/" + customer + "/demand-conditions",
                            "{\"dealType\":\"MONTHLY\",\"categoryId\":"
                                + categoryIdOf("OFFICETEL")
                                + ",\"depositMin\":10000000,\"depositMax\":30000000,\"regions\":[\"강남구\"]}",
                            cookie))
                    .andReturn())
            .get("conditionId")
            .longValue();
    mockMvc
        .perform(authedGet("/api/demand-conditions/" + condition + "/candidate-properties", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].propertyId").value(fit))
        .andExpect(jsonPath("$.items[0].depositAmount").value(20000000))
        .andExpect(jsonPath("$.items[0].monthlyRentAmount").value(900000))
        .andExpect(jsonPath("$.items[0].salePrice").doesNotExist());
    assertError(
        mockMvc.perform(
            authedGet(
                "/api/demand-conditions/" + condition + "/candidate-properties", cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet("/api/demand-conditions/99999/candidate-properties", cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
