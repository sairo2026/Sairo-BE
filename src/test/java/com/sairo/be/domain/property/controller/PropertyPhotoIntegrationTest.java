package com.sairo.be.domain.property.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.property.service.PhotoSanitizerTest;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.PropertyIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PropertyPhotoIntegrationTest extends PropertyIntegrationTestBase {

  private String photos(long propertyId) {
    return "/api/properties/" + propertyId + "/photos";
  }

  private long upload(Cookie cookie, long propertyId, byte[] content, String type)
      throws Exception {
    return AuthFlowSupport.json(
            mockMvc
                .perform(photoUpload(photos(propertyId), cookie, content, type, null))
                .andExpect(status().isCreated())
                .andReturn())
        .get("photoId")
        .longValue();
  }

  @Test
  void JPG를_올리면_EXIF를_제거해_저장하고_서명_URL과_올라간_버전을_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "사진", categoryIdOf("OFFICETEL"));
    mockMvc
        .perform(
            photoUpload(photos(id), cookie, PhotoSanitizerTest.jpegWithExif(), "image/jpeg", "거실"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.description").value("거실"))
        .andExpect(jsonPath("$.sortOrder").value(1))
        .andExpect(jsonPath("$.url").isString())
        .andExpect(jsonPath("$.urlExpiresAt").isString())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(
            jsonPath("$.objectKey")
                .value(org.hamcrest.Matchers.startsWith("properties/" + id + "/")));
    String key =
        jdbcTemplate.queryForObject(
            "SELECT object_key FROM property_photo WHERE property_id = ?", String.class, id);
    byte[] stored = objectStorage.find(key).orElseThrow();
    assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude");
    assertThat(key).endsWith(".jpg");
  }

  @Test
  void PNG와_WebP도_올릴_수_있고_정렬_순서를_따로_받는다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "형식", categoryIdOf("OFFICETEL"));
    upload(cookie, id, PhotoSanitizerTest.pngWithMetadata(), "image/png");
    upload(cookie, id, PhotoSanitizerTest.webpWithExif(), "image/webp");
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT object_key FROM property_photo WHERE property_id = ? ORDER BY id",
                String.class,
                id))
        .hasSize(2)
        .satisfies(
            keys -> {
              assertThat(keys.get(0)).endsWith(".png");
              assertThat(keys.get(1)).endsWith(".webp");
            });
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT sort_order FROM property_photo WHERE property_id = ? ORDER BY id",
                Integer.class,
                id))
        .containsExactly(1, 2);
  }

  @Test
  void 지원하지_않는_형식_빈_파일_큰_파일은_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "검증", categoryIdOf("OFFICETEL"));
    assertFieldError(
        mockMvc.perform(
            photoUpload(
                photos(id),
                cookie,
                "GIF89a....".getBytes(StandardCharsets.US_ASCII),
                "image/gif",
                null)),
        "file",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(photoUpload(photos(id), cookie, new byte[0], "image/png", null)),
        "file",
        "REQUIRED");
    byte[] huge = new byte[10 * 1024 * 1024 + 1];
    byte[] png = PhotoSanitizerTest.minimalPng();
    System.arraycopy(png, 0, huge, 0, png.length);
    assertFieldError(
        mockMvc.perform(photoUpload(photos(id), cookie, huge, "image/png", null)),
        "file",
        "TOO_LONG");
    assertError(
        mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                    photos(id))
                .cookie(cookie)
                .cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
                .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE)),
        ErrorCode.INVALID_REQUEST);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM property_photo WHERE property_id = ?", Long.class, id))
        .isZero();
  }

  @Test
  void 스물한_번째_사진은_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "스무장", categoryIdOf("OFFICETEL"));
    byte[] png = PhotoSanitizerTest.minimalPng();
    for (int i = 0; i < 20; i++) {
      upload(cookie, id, png, "image/png");
    }
    assertFieldError(
        mockMvc.perform(photoUpload(photos(id), cookie, png, "image/png", null)),
        "file",
        "OUT_OF_RANGE");
  }

  @Test
  void 보관된_매물과_다른_사무소_매물에는_올릴_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    long id = createProperty(cookieOf(owner), "격리", categoryIdOf("OFFICETEL"));
    byte[] png = PhotoSanitizerTest.minimalPng();
    assertError(
        mockMvc.perform(photoUpload(photos(id), cookieOf(other), png, "image/png", null)),
        ErrorCode.RESOURCE_NOT_FOUND);
    archive(id);
    assertError(
        mockMvc.perform(photoUpload(photos(id), cookieOf(owner), png, "image/png", null)),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 설명과_정렬_순서를_보낸_필드만_바꾸고_매물_버전을_올린다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "수정", categoryIdOf("OFFICETEL"));
    long photoId = upload(cookie, id, PhotoSanitizerTest.minimalPng(), "image/png");
    String url = photos(id) + "/" + photoId;
    mockMvc
        .perform(jsonPatchAs(url, "{\"description\":\"침실\",\"sortOrder\":5}", cookie, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("침실"))
        .andExpect(jsonPath("$.sortOrder").value(5))
        .andExpect(jsonPath("$.url").isString())
        .andExpect(jsonPath("$.version").value(3));
    mockMvc
        .perform(jsonPatchAs(url, "{\"description\":null}", cookie, 3))
        .andExpect(jsonPath("$.description").doesNotExist())
        .andExpect(jsonPath("$.sortOrder").value(5))
        .andExpect(jsonPath("$.version").value(4));
  }

  @Test
  void 사진_수정의_잘못된_요청은_각각_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "오류", categoryIdOf("OFFICETEL"));
    long photoId = upload(cookie, id, PhotoSanitizerTest.minimalPng(), "image/png");
    String url = photos(id) + "/" + photoId;
    assertError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, 2)), ErrorCode.INVALID_REQUEST);
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"sortOrder\":\"1\"}", cookie, 2)),
        "sortOrder",
        "TYPE_MISMATCH");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"objectKey\":\"x\"}", cookie, 2)),
        "objectKey",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"sortOrder\":1}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"sortOrder\":1}", cookie, 1)),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(jsonPatchAs(photos(id) + "/99999", "{\"sortOrder\":1}", cookie, 2)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"sortOrder\":1}", cookieOf(other), 2)),
        ErrorCode.RESOURCE_NOT_FOUND);
    archive(id);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"sortOrder\":1}", cookie, 2)),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 삭제하면_행과_저장소_객체가_사라지고_버전이_오른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "삭제", categoryIdOf("OFFICETEL"));
    long photoId = upload(cookie, id, PhotoSanitizerTest.minimalPng(), "image/png");
    String key =
        jdbcTemplate.queryForObject(
            "SELECT object_key FROM property_photo WHERE id = ?", String.class, photoId);
    assertError(
        mockMvc.perform(deleteAs(photos(id) + "/" + photoId, cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    mockMvc.perform(deleteAs(photos(id) + "/" + photoId, cookie)).andExpect(status().isNoContent());
    assertThat(objectStorage.find(key)).isEmpty();
    assertThat(propertyVersion(id)).isEqualTo(3);
    assertError(
        mockMvc.perform(deleteAs(photos(id) + "/" + photoId, cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    long second = upload(cookie, id, PhotoSanitizerTest.minimalPng(), "image/png");
    archive(id);
    assertError(
        mockMvc.perform(deleteAs(photos(id) + "/" + second, cookie)), ErrorCode.PROPERTY_ARCHIVED);
  }
}
