package com.sairo.be.domain.property.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.PropertyIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PropertyOfferIntegrationTest extends PropertyIntegrationTestBase {

  private String offers(long propertyId) {
    return "/api/properties/" + propertyId + "/offers";
  }

  private long createOffer(Cookie cookie, long propertyId, String body) throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(offers(propertyId), body, cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("offerId")
        .longValue();
  }

  @Test
  void 유형별_가격으로_등록하고_매물_버전을_올린다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "제안", categoryIdOf("OFFICETEL"));
    mockMvc
        .perform(
            jsonPostAs(
                offers(id),
                "{\"dealType\":\"MONTHLY\",\"depositAmount\":20000000,\"monthlyRentAmount\":900000}",
                cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.dealType").value("MONTHLY"))
        .andExpect(jsonPath("$.depositAmount").value(20000000))
        .andExpect(jsonPath("$.salePrice").doesNotExist())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(2));
    createOffer(cookie, id, "{\"dealType\":\"SALE\",\"salePrice\":500000000}");
    createOffer(cookie, id, "{\"dealType\":\"JEONSE\",\"depositAmount\":300000000}");
    mockMvc
        .perform(authedGet(offers(id), cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].dealType").value("MONTHLY"))
        .andExpect(jsonPath("$.items[0].officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.items[0].version").value(4));
    mockMvc
        .perform(authedGet("/api/properties/" + id, cookie))
        .andExpect(jsonPath("$.offers.length()").value(3));
  }

  @Test
  void 유형에_맞지_않거나_빠진_가격은_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "검증", categoryIdOf("OFFICETEL"));
    assertFieldError(
        mockMvc.perform(jsonPostAs(offers(id), "{\"dealType\":\"SALE\"}", cookie)),
        "salePrice",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                offers(id), "{\"dealType\":\"SALE\",\"salePrice\":1,\"depositAmount\":1}", cookie)),
        "depositAmount",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(offers(id), "{\"dealType\":\"MONTHLY\",\"depositAmount\":1}", cookie)),
        "monthlyRentAmount",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(offers(id), "{\"dealType\":\"JEONSE\",\"depositAmount\":-1}", cookie)),
        "depositAmount",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(jsonPostAs(offers(id), "{\"salePrice\":1}", cookie)),
        "dealType",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPostAs(offers(id), "{\"dealType\":\"RENT\"}", cookie)),
        "dealType",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(offers(id), "{\"dealType\":\"SALE\",\"salePrice\":1.5}", cookie)),
        "salePrice",
        "TYPE_MISMATCH");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                offers(id), "{\"dealType\":\"SALE\",\"salePrice\":1,\"status\":\"X\"}", cookie)),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                offers(id),
                "{\"dealType\":\"SALE\",\"salePrice\":1,\"premiumAmount\":-5}",
                cookie)),
        "premiumAmount",
        "NOT_ALLOWED");
  }

  @Test
  void 분양권_매물만_음수_프리미엄을_받는다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "분양권", categoryIdOf("PRESALE_RIGHT"));
    mockMvc
        .perform(
            jsonPostAs(
                offers(id),
                "{\"dealType\":\"SALE\",\"salePrice\":100,\"premiumAmount\":-30000000}",
                cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.premiumAmount").value(-30000000));
  }

  @Test
  void 같은_유형의_활성_제안이_있으면_409이고_종료되면_다시_등록할_수_있다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "중복", categoryIdOf("OFFICETEL"));
    long first = createOffer(cookie, id, "{\"dealType\":\"SALE\",\"salePrice\":1}");
    assertError(
        mockMvc.perform(jsonPostAs(offers(id), "{\"dealType\":\"SALE\",\"salePrice\":2}", cookie)),
        ErrorCode.OFFER_ALREADY_ACTIVE);
    mockMvc
        .perform(jsonPatchAs(offers(id) + "/" + first, "{\"status\":\"WITHDRAWN\"}", cookie, 2))
        .andExpect(status().isOk());
    createOffer(cookie, id, "{\"dealType\":\"SALE\",\"salePrice\":2}");
  }

  @Test
  void 상태는_협의중을_거치지_않아도_바꾸고_협의중단은_거래가능으로_돌아간다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "전이", categoryIdOf("OFFICETEL"));
    long offer = createOffer(cookie, id, "{\"dealType\":\"SALE\",\"salePrice\":1}");
    String url = offers(id) + "/" + offer;
    mockMvc
        .perform(jsonPatchAs(url, "{\"status\":\"NEGOTIATING\"}", cookie, 2))
        .andExpect(jsonPath("$.status").value("NEGOTIATING"))
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(jsonPatchAs(url, "{\"status\":\"AVAILABLE\"}", cookie, 3))
        .andExpect(jsonPath("$.status").value("AVAILABLE"));
    mockMvc
        .perform(jsonPatchAs(url, "{\"status\":\"COMPLETED\",\"salePrice\":9}", cookie, 4))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.salePrice").value(9));
  }

  @Test
  void 종료_상태는_다른_상태나_가격으로_바꿀_수_없다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "종료", categoryIdOf("OFFICETEL"));
    long offer = createOffer(cookie, id, "{\"dealType\":\"SALE\",\"salePrice\":1}");
    String url = offers(id) + "/" + offer;
    mockMvc
        .perform(jsonPatchAs(url, "{\"status\":\"COMPLETED\"}", cookie, 2))
        .andExpect(status().isOk());
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"status\":\"AVAILABLE\"}", cookie, 3)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"salePrice\":5}", cookie, 3)),
        ErrorCode.INVALID_TRANSITION);
    mockMvc
        .perform(jsonPatchAs(url, "{\"status\":\"COMPLETED\"}", cookie, 3))
        .andExpect(status().isOk());
  }

  @Test
  void 가격_수정은_유형에_맞는_값만_받는다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "가격", categoryIdOf("OFFICETEL"));
    long offer =
        createOffer(
            cookie, id, "{\"dealType\":\"MONTHLY\",\"depositAmount\":10,\"monthlyRentAmount\":1}");
    String url = offers(id) + "/" + offer;
    mockMvc
        .perform(jsonPatchAs(url, "{\"monthlyRentAmount\":2}", cookie, 2))
        .andExpect(jsonPath("$.monthlyRentAmount").value(2))
        .andExpect(jsonPath("$.depositAmount").value(10));
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"salePrice\":3}", cookie, 3)),
        "salePrice",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"depositAmount\":null}", cookie, 3)),
        "depositAmount",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"status\":\"DONE\"}", cookie, 3)),
        "status",
        "NOT_ALLOWED");
    assertError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, 3)), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 수정은_버전_헤더와_대상_존재_보관_상태를_검사한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "검사", categoryIdOf("OFFICETEL"));
    long offer = createOffer(cookie, id, "{\"dealType\":\"SALE\",\"salePrice\":1}");
    String url = offers(id) + "/" + offer;
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"salePrice\":2}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"salePrice\":2}", cookie, 1)),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(jsonPatchAs(offers(id) + "/99999", "{\"salePrice\":2}", cookie, 2)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"salePrice\":2}", cookieOf(other), 2)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(offers(id), cookieOf(other))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                offers(id), "{\"dealType\":\"JEONSE\",\"depositAmount\":1}", cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    archive(id);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"salePrice\":2}", cookie, 2)),
        ErrorCode.PROPERTY_ARCHIVED);
    assertError(
        mockMvc.perform(
            jsonPostAs(offers(id), "{\"dealType\":\"JEONSE\",\"depositAmount\":1}", cookie)),
        ErrorCode.PROPERTY_ARCHIVED);
    mockMvc.perform(authedGet(offers(id), cookie)).andExpect(status().isOk());
    assertThat(propertyVersion(id)).isEqualTo(2);
  }
}
