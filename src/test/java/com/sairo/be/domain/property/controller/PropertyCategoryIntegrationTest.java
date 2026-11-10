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
class PropertyCategoryIntegrationTest extends PropertyIntegrationTestBase {

  private long createCategory(Cookie cookie, String name) throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs("/api/property-categories", "{\"name\":\"" + name + "\"}", cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("categoryId")
        .longValue();
  }

  private long createField(Cookie cookie, long categoryId, String name, String type)
      throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/property-categories/" + categoryId + "/fields",
                        "{\"fieldName\":\"" + name + "\",\"fieldType\":\"" + type + "\"}",
                        cookie))
                .andExpect(status().isCreated())
                .andReturn())
        .get("fieldDefinitionId")
        .longValue();
  }

  @Test
  void 목록은_기본_10종과_자기_사무소_커스텀만_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    createCategory(cookie, "타운하우스");
    createCategory(cookieOf(other), "타 사무소");
    mockMvc
        .perform(authedGet("/api/property-categories", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(11))
        .andExpect(jsonPath("$.items[0].isSystem").value(true))
        .andExpect(jsonPath("$.items[0].name").value("상업용건물"))
        .andExpect(jsonPath("$.items[10].name").value("타운하우스"))
        .andExpect(jsonPath("$.items[10].isSystem").value(false))
        .andExpect(jsonPath("$.items[10].active").value(true));
  }

  @Test
  void 커스텀_카테고리_추가는_사무소_번호와_생성시각을_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.AGENT);
    mockMvc
        .perform(jsonPostAs("/api/property-categories", "{\"name\":\" 타운하우스 \"}", cookieOf(owner)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("타운하우스"))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.isSystem").value(false))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.createdAt").isString());
  }

  @Test
  void 이름이_비었거나_너무_길거나_없으면_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    assertFieldError(
        mockMvc.perform(jsonPostAs("/api/property-categories", "{\"name\":\"  \"}", cookie)),
        "name",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPostAs("/api/property-categories", "{}", cookie)), "name", "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/property-categories", "{\"name\":\"" + "가".repeat(51) + "\"}", cookie)),
        "name",
        "TOO_LONG");
  }

  @Test
  void 같은_이름은_기본_카테고리_이름과도_중복으로_본다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    createCategory(cookie, "타운하우스");
    assertError(
        mockMvc.perform(jsonPostAs("/api/property-categories", "{\"name\":\"타운하우스\"}", cookie)),
        ErrorCode.CATEGORY_NAME_DUPLICATED);
    assertError(
        mockMvc.perform(jsonPostAs("/api/property-categories", "{\"name\":\"아파트\"}", cookie)),
        ErrorCode.CATEGORY_NAME_DUPLICATED);
    createCategory(cookieOf(other), "타운하우스");
  }

  @Test
  void 활성_커스텀_카테고리는_스무_개까지이고_비활성은_세지_않는다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long first = 0;
    for (int i = 1; i <= 20; i++) {
      long id = createCategory(cookie, "분류" + i);
      if (i == 1) {
        first = id;
      }
    }
    assertError(
        mockMvc.perform(jsonPostAs("/api/property-categories", "{\"name\":\"분류21\"}", cookie)),
        ErrorCode.CATEGORY_LIMIT_EXCEEDED);
    mockMvc
        .perform(
            jsonPatchAs("/api/property-categories/" + first, "{\"active\":false}", cookie, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    createCategory(cookie, "분류21");
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/property-categories/" + first, "{\"active\":true}", cookie, null)),
        ErrorCode.CATEGORY_LIMIT_EXCEEDED);
  }

  @Test
  void 이름_수정과_비활성화는_커스텀_카테고리만_되고_기본_10종은_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createCategory(cookie, "타운하우스");
    createCategory(cookie, "다른분류");
    mockMvc
        .perform(jsonPatchAs("/api/property-categories/" + id, "{\"name\":\"신규\"}", cookie, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("신규"));
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/property-categories/" + id, "{\"name\":\"다른분류\"}", cookie, null)),
        ErrorCode.CATEGORY_NAME_DUPLICATED);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/property-categories/" + categoryIdOf("APARTMENT"),
                "{\"active\":false}",
                cookie,
                null)),
        ErrorCode.SYSTEM_CATEGORY_IMMUTABLE);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/property-categories/" + id, "{\"name\":\"x\"}", cookieOf(other), null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPatchAs("/api/property-categories/" + id, "{}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/property-categories/999999", "{\"active\":false}", cookie, null)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 매물이_연결된_카테고리를_비활성화해도_기존_매물은_유지되고_새_선택만_막힌다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createCategory(cookie, "타운하우스");
    long propertyId = createProperty(cookie, "연결", id);
    mockMvc
        .perform(jsonPatchAs("/api/property-categories/" + id, "{\"active\":false}", cookie, null))
        .andExpect(status().isOk());
    mockMvc
        .perform(authedGet("/api/properties/" + propertyId, cookie))
        .andExpect(jsonPath("$.categoryId").value(id))
        .andExpect(jsonPath("$.categoryName").value("타운하우스"));
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/properties", "{\"name\":\"새\",\"categoryId\":" + id + "}", cookie)),
        "categoryId",
        "NOT_ALLOWED");
  }

  @Test
  void 필드_정의_목록은_기본_필드의_단위와_범위를_돌려준다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    mockMvc
        .perform(
            authedGet(
                "/api/property-categories/" + categoryIdOf("RETAIL_OFFICE") + "/fields", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5))
        .andExpect(jsonPath("$.items[0].fieldName").value("해당층"))
        .andExpect(jsonPath("$.items[0].fieldType").value("NUMBER"))
        .andExpect(jsonPath("$.items[0].numberKind").value("INTEGER"))
        .andExpect(jsonPath("$.items[0].unit").value("층"))
        .andExpect(jsonPath("$.items[0].minValue").value(0))
        .andExpect(jsonPath("$.items[3].fieldName").value("전용률"))
        .andExpect(jsonPath("$.items[3].numberKind").value("DECIMAL"))
        .andExpect(jsonPath("$.items[3].unit").value("%"))
        .andExpect(jsonPath("$.items[3].maxValue").value(100))
        .andExpect(jsonPath("$.items[0].active").value(true));
    mockMvc
        .perform(
            authedGet(
                "/api/property-categories/" + categoryIdOf("PRESALE_RIGHT") + "/fields", cookie))
        .andExpect(jsonPath("$.items[5].fieldName").value("프리미엄"))
        .andExpect(jsonPath("$.items[5].minValue").doesNotExist());
    assertError(
        mockMvc.perform(authedGet("/api/property-categories/999999/fields", cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 커스텀_필드_추가는_숫자를_DECIMAL로_만들고_다른_사무소에는_보이지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long apartment = categoryIdOf("APARTMENT");
    long fieldId = createField(cookie, apartment, "조망", "TEXT");
    long numberId = createField(cookie, apartment, "채광 점수", "NUMBER");
    mockMvc
        .perform(authedGet("/api/property-categories/" + apartment + "/fields", cookie))
        .andExpect(jsonPath("$.items.length()").value(9));
    mockMvc
        .perform(authedGet("/api/property-categories/" + apartment + "/fields", cookieOf(other)))
        .andExpect(jsonPath("$.items.length()").value(7));
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT number_kind, unit, min_value, max_value, office_id FROM"
                    + " property_custom_field_definition WHERE id = ?",
                numberId))
        .containsEntry("number_kind", "DECIMAL")
        .containsEntry("unit", null)
        .containsEntry("min_value", null)
        .containsEntry("office_id", owner.office().getId());
    assertThat(fieldId).isNotEqualTo(numberId);
  }

  @Test
  void 필드_이름_중복과_개수_상한은_409이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long categoryId = createCategory(cookie, "필드 상한");
    long first = createField(cookie, categoryId, "필드1", "TEXT");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/property-categories/" + categoryId + "/fields",
                "{\"fieldName\":\"필드1\",\"fieldType\":\"TEXT\"}",
                cookie)),
        ErrorCode.FIELD_NAME_DUPLICATED);
    for (int i = 2; i <= 50; i++) {
      createField(cookie, categoryId, "필드" + i, "BOOLEAN");
    }
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/property-categories/" + categoryId + "/fields",
                "{\"fieldName\":\"필드51\",\"fieldType\":\"DATE\"}",
                cookie)),
        ErrorCode.FIELD_LIMIT_EXCEEDED);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/property-categories/" + categoryId + "/fields/" + first,
                "{\"active\":false}",
                cookie,
                null))
        .andExpect(status().isOk());
    createField(cookie, categoryId, "필드51", "DATE");
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/property-categories/" + categoryId + "/fields/" + first,
                "{\"active\":true}",
                cookie,
                null)),
        ErrorCode.FIELD_LIMIT_EXCEEDED);
  }

  @Test
  void 필드_요청의_형식_오류는_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long categoryId = createCategory(cookie, "형식");
    String url = "/api/property-categories/" + categoryId + "/fields";
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, "{\"fieldType\":\"TEXT\"}", cookie)),
        "fieldName",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, "{\"fieldName\":\"가\"}", cookie)), "fieldType", "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, "{\"fieldName\":\"가\",\"fieldType\":\"MONEY\"}", cookie)),
        "fieldType",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url, "{\"fieldName\":\"" + "가".repeat(51) + "\",\"fieldType\":\"TEXT\"}", cookie)),
        "fieldName",
        "TOO_LONG");
  }

  @Test
  void 필드_수정은_이름과_활성만_바꾸고_기본_필드는_403_다른_사무소는_404이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long categoryId = createCategory(cookie, "수정용");
    long fieldId = createField(cookie, categoryId, "원래", "TEXT");
    createField(cookie, categoryId, "다른", "TEXT");
    String url = "/api/property-categories/" + categoryId + "/fields/" + fieldId;
    mockMvc
        .perform(jsonPatchAs(url, "{\"fieldName\":\"바뀜\",\"active\":false}", cookie, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fieldName").value("바뀜"))
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.fieldType").value("TEXT"));
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"fieldName\":\"다른\"}", cookie, null)),
        ErrorCode.FIELD_NAME_DUPLICATED);
    assertError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, null)), ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"active\":true}", cookieOf(other), null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/property-categories/" + categoryId + "/fields/999999",
                "{\"active\":true}",
                cookie,
                null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/property-categories/"
                    + categoryIdOf("LAND")
                    + "/fields/"
                    + fieldIdOf("LAND", "지목"),
                "{\"active\":false}",
                cookie,
                null)),
        ErrorCode.SYSTEM_CATEGORY_IMMUTABLE);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/property-categories/" + categoryIdOf("APARTMENT") + "/fields/" + fieldId,
                "{\"active\":true}",
                cookie,
                null)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
