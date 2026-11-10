package com.sairo.be.domain.property.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.property.service.PhotoSanitizerTest;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.PropertyIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.RequestBuilder;

@IntegrationTest
class PropertyAccessMatrixIntegrationTest extends PropertyIntegrationTestBase {

  private Map<String, Function<Cookie, RequestBuilder>> endpoints(long propertyId, long photoId) {
    long floor = fieldIdOf("OFFICETEL", "해당층");
    long category = categoryIdOf("OFFICETEL");
    long field = fieldIdOf("OFFICETEL", "주용도");
    Map<String, Function<Cookie, RequestBuilder>> map = new LinkedHashMap<>();
    map.put("GET /api/properties", c -> authedGet("/api/properties", c));
    map.put(
        "POST /api/properties",
        c -> jsonPostAs("/api/properties", "{\"name\":\"가\",\"categoryId\":" + category + "}", c));
    map.put(
        "GET /api/properties/duplicate-check",
        c -> authedGet("/api/properties/duplicate-check", c).param("address", "주소"));
    map.put("GET /api/properties/{id}", c -> authedGet("/api/properties/" + propertyId, c));
    map.put(
        "PATCH /api/properties/{id}",
        c -> jsonPatchAs("/api/properties/" + propertyId, "{\"name\":\"나\"}", c, 1));
    map.put(
        "POST /api/properties/{id}/photos",
        c ->
            photoUpload(
                "/api/properties/" + propertyId + "/photos",
                c,
                PhotoSanitizerTest.minimalPng(),
                "image/png",
                null));
    map.put(
        "PATCH /api/properties/{id}/photos/{photoId}",
        c ->
            jsonPatchAs(
                "/api/properties/" + propertyId + "/photos/" + photoId, "{\"sortOrder\":2}", c, 2));
    map.put(
        "DELETE /api/properties/{id}/photos/{photoId}",
        c -> deleteAs("/api/properties/" + propertyId + "/photos/" + photoId, c));
    map.put(
        "PUT /api/properties/{id}/fields/{fieldId}",
        c ->
            jsonPutAs("/api/properties/" + propertyId + "/fields/" + floor, "{\"value\":3}", c, 1));
    map.put("GET /api/property-categories", c -> authedGet("/api/property-categories", c));
    map.put(
        "POST /api/property-categories",
        c -> jsonPostAs("/api/property-categories", "{\"name\":\"분류\"}", c));
    map.put(
        "PATCH /api/property-categories/{id}",
        c -> jsonPatchAs("/api/property-categories/" + category, "{\"active\":true}", c, null));
    map.put(
        "GET /api/property-categories/{id}/fields",
        c -> authedGet("/api/property-categories/" + category + "/fields", c));
    map.put(
        "POST /api/property-categories/{id}/fields",
        c ->
            jsonPostAs(
                "/api/property-categories/" + category + "/fields",
                "{\"fieldName\":\"새\",\"fieldType\":\"TEXT\"}",
                c));
    map.put(
        "PATCH /api/property-categories/{id}/fields/{fieldId}",
        c ->
            jsonPatchAs(
                "/api/property-categories/" + category + "/fields/" + field,
                "{\"active\":true}",
                c,
                null));
    return map;
  }

  private long[] fixture(Cookie ownerCookie) throws Exception {
    long propertyId = createProperty(ownerCookie, "기준", categoryIdOf("OFFICETEL"));
    var photo =
        com.sairo.be.support.AuthFlowSupport.json(
            mockMvc
                .perform(
                    photoUpload(
                        "/api/properties/" + propertyId + "/photos",
                        ownerCookie,
                        PhotoSanitizerTest.minimalPng(),
                        "image/png",
                        null))
                .andReturn());
    return new long[] {propertyId, photo.get("photoId").longValue()};
  }

  @Test
  void 로그인이_없으면_모든_엔드포인트가_401이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long[] ids = fixture(cookieOf(owner));
    for (var entry : endpoints(ids[0], ids[1]).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(null)), ErrorCode.UNAUTHENTICATED);
    }
  }

  @Test
  void 소속이_없는_회원은_모든_엔드포인트가_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long[] ids = fixture(cookieOf(owner));
    Cookie plain = kakaoSessionFor(saveEmailUser("nomember@example.com", "010-1212-3434"));
    for (var entry : endpoints(ids[0], ids[1]).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(plain)), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 중개보조원은_모든_엔드포인트가_403이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long[] ids = fixture(cookieOf(owner));
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    for (var entry : endpoints(ids[0], ids[1]).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(deputy)), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 대표와_소속공인중개사는_모든_엔드포인트를_호출할_수_있다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long[] ids = fixture(cookieOf(owner));
    Cookie agent = cookieOf(staffIn(owner.office(), OfficeMembershipRole.AGENT));
    for (var entry : endpoints(ids[0], ids[1]).entrySet()) {
      String body =
          mockMvc
              .perform(entry.getValue().apply(agent))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).as(entry.getKey()).doesNotContain("\"UNAUTHENTICATED\"", "\"FORBIDDEN\"");
    }
  }

  @Test
  void 다른_사무소_구성원은_매물_하위_엔드포인트에서_404를_받는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long[] ids = fixture(cookieOf(owner));
    Cookie foreign = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    for (var entry : endpoints(ids[0], ids[1]).entrySet()) {
      if (!entry.getKey().contains("{id}") || entry.getKey().contains("property-categories")) {
        continue;
      }
      mockMvc.perform(entry.getValue().apply(foreign)).andExpect(status().isNotFound());
    }
  }
}
