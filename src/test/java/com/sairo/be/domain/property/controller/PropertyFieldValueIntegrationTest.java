package com.sairo.be.domain.property.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.PropertyIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PropertyFieldValueIntegrationTest extends PropertyIntegrationTestBase {

  private String fieldUrl(long propertyId, long fieldId) {
    return "/api/properties/" + propertyId + "/fields/" + fieldId;
  }

  @Test
  void 타입별_값을_저장하고_수정하면_버전이_오른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "필드", categoryIdOf("OFFICETEL"));
    long floor = fieldIdOf("OFFICETEL", "해당층");
    long main = fieldIdOf("OFFICETEL", "주용도");
    long built = fieldIdOf("OFFICETEL", "준공일");
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":12}", cookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fieldDefinitionId").value(floor))
        .andExpect(jsonPath("$.value").value(12))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(2));
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, main), "{\"value\":\"업무시설\"}", cookie, 2))
        .andExpect(jsonPath("$.value").value("업무시설"))
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, built), "{\"value\":\"2020-05-01\"}", cookie, 3))
        .andExpect(jsonPath("$.value").value("2020-05-01"));
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":13}", cookie, 4))
        .andExpect(jsonPath("$.value").value(13))
        .andExpect(jsonPath("$.version").value(5));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM property_field_value WHERE property_id = ?", Long.class, id))
        .isEqualTo(3);
  }

  @Test
  void 같은_값을_다시_보내면_버전을_올리지_않고_null은_값을_비운다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "비움", categoryIdOf("OFFICETEL"));
    long floor = fieldIdOf("OFFICETEL", "해당층");
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":5}", cookie, 1))
        .andExpect(status().isOk());
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":5}", cookie, 2))
        .andExpect(jsonPath("$.version").value(2));
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":null}", cookie, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").doesNotExist())
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":null}", cookie, 3))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM property_field_value WHERE property_id = ?", Long.class, id))
        .isZero();
  }

  @Test
  void 타입_정수여부_최솟값_최댓값을_어기면_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long retail = createProperty(cookie, "상가", categoryIdOf("RETAIL_OFFICE"));
    long floor = fieldIdOf("RETAIL_OFFICE", "해당층");
    long ratio = fieldIdOf("RETAIL_OFFICE", "전용률");
    long lift = fieldIdOf("RETAIL_OFFICE", "승강기");
    long limit = fieldIdOf("RETAIL_OFFICE", "업종 제한");
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(retail, floor), "{\"value\":\"12\"}", cookie, 1)),
        "value",
        "TYPE_MISMATCH");
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(retail, floor), "{\"value\":1.5}", cookie, 1)),
        "value",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(retail, floor), "{\"value\":-1}", cookie, 1)),
        "value",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(retail, ratio), "{\"value\":100.5}", cookie, 1)),
        "value",
        "OUT_OF_RANGE");
    mockMvc
        .perform(jsonPutAs(fieldUrl(retail, ratio), "{\"value\":87.5}", cookie, 1))
        .andExpect(jsonPath("$.value").value(87.5));
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(retail, lift), "{\"value\":\"true\"}", cookie, 2)),
        "value",
        "TYPE_MISMATCH");
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(retail, limit), "{\"value\":3}", cookie, 2)),
        "value",
        "TYPE_MISMATCH");
    mockMvc
        .perform(jsonPutAs(fieldUrl(retail, lift), "{\"value\":true}", cookie, 2))
        .andExpect(jsonPath("$.value").value(true));
    assertFieldError(
        mockMvc.perform(
            jsonPutAs(fieldUrl(retail, fieldIdOf("RETAIL_OFFICE", "권리금")), "{}", cookie, 3)),
        "value",
        "REQUIRED");
  }

  @Test
  void 분양권_프리미엄만_음수를_받는다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "분양권", categoryIdOf("PRESALE_RIGHT"));
    long premium = fieldIdOf("PRESALE_RIGHT", "프리미엄");
    long price = fieldIdOf("PRESALE_RIGHT", "분양가");
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, premium), "{\"value\":-30000000}", cookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value").value(-30000000));
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, price), "{\"value\":-1}", cookie, 2)),
        "value",
        "OUT_OF_RANGE");
  }

  @Test
  void 사무소_커스텀_숫자_필드는_소수를_받고_비활성_필드는_409이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long categoryId =
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(jsonPostAs("/api/property-categories", "{\"name\":\"창고형\"}", cookie))
                    .andReturn())
            .get("categoryId")
            .longValue();
    long fieldId =
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/property-categories/" + categoryId + "/fields",
                            "{\"fieldName\":\"천장 높이\",\"fieldType\":\"NUMBER\"}",
                            cookie))
                    .andReturn())
            .get("fieldDefinitionId")
            .longValue();
    long id = createProperty(cookie, "커스텀", categoryId);
    mockMvc
        .perform(jsonPutAs(fieldUrl(id, fieldId), "{\"value\":-2.75}", cookie, 1))
        .andExpect(jsonPath("$.value").value(-2.75));
    jdbcTemplate.update(
        "UPDATE property_custom_field_definition SET active = false WHERE id = ?", fieldId);
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, fieldId), "{\"value\":1}", cookie, 2)),
        ErrorCode.FIELD_INACTIVE);
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, fieldId), "{\"value\":null}", cookie, 2)),
        ErrorCode.FIELD_INACTIVE);
  }

  @Test
  void 매물_카테고리의_필드가_아니면_400이고_없거나_다른_사무소_자원은_404이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "불일치", categoryIdOf("OFFICETEL"));
    long landField = fieldIdOf("LAND", "지목");
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, landField), "{\"value\":\"대\"}", cookie, 1)),
        "fieldDefinitionId",
        "NOT_ALLOWED");
    long floor = fieldIdOf("OFFICETEL", "해당층");
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, 999999), "{\"value\":1}", cookie, 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(999999, floor), "{\"value\":1}", cookie, 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":1}", cookieOf(other), 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    long foreignCategory =
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/property-categories", "{\"name\":\"타사 전용\"}", cookieOf(other)))
                    .andReturn())
            .get("categoryId")
            .longValue();
    long foreignField =
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/property-categories/" + foreignCategory + "/fields",
                            "{\"fieldName\":\"비공개\",\"fieldType\":\"TEXT\"}",
                            cookieOf(other)))
                    .andReturn())
            .get("fieldDefinitionId")
            .longValue();
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, foreignField), "{\"value\":\"x\"}", cookie, 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 버전_헤더와_보관_상태를_검사한다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "버전", categoryIdOf("OFFICETEL"));
    long floor = fieldIdOf("OFFICETEL", "해당층");
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":1}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":1}", cookie, 9)),
        ErrorCode.VERSION_CONFLICT);
    assertFieldError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":1,\"x\":2}", cookie, 1)),
        "x",
        "NOT_ALLOWED");
    archive(id);
    assertError(
        mockMvc.perform(jsonPutAs(fieldUrl(id, floor), "{\"value\":1}", cookie, 1)),
        ErrorCode.PROPERTY_ARCHIVED);
  }
}
