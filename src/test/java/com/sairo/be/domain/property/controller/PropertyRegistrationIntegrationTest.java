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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PropertyRegistrationIntegrationTest extends PropertyIntegrationTestBase {

  private String draftBody(long categoryId, String extra) {
    return "{\"name\":\"강남역 오피스텔\",\"categoryId\":" + categoryId + extra + "}";
  }

  @Test
  void 이름과_카테고리만으로_임시저장하고_생성_사건을_같은_시각으로_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long categoryId = categoryIdOf("OFFICETEL");
    mockMvc
        .perform(jsonPostAs("/api/properties", draftBody(categoryId, ""), cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.registrationStage").value("DRAFT"))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.propertyId").isNumber());
    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT e.event_type, e.creation_source, e.recorded_by_membership_id,"
                + " e.effective_at = p.created_at AS same_time FROM entity_lifecycle_event e JOIN"
                + " property p ON p.id = e.property_id");
    assertThat(event.get("event_type")).isEqualTo("CREATED");
    assertThat(event.get("creation_source")).isEqualTo("MANUAL");
    assertThat(event.get("recorded_by_membership_id")).isEqualTo(owner.membership().getId());
    assertThat(event.get("same_time")).isEqualTo(true);
  }

  @Test
  void 모든_공통필드를_넣어_등록하고_상세에서_그대로_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long categoryId = categoryIdOf("OFFICETEL");
    String extra =
        ",\"address\":\"서울 강남구 테헤란로 123\",\"addressDetail\":\"1201호\","
            + "\"managingMembershipId\":"
            + owner.membership().getId()
            + ",\"exclusiveAreaSqm\":59.8,\"supplyAreaSqm\":75.2,\"landAreaSqm\":10,"
            + "\"grossFloorAreaSqm\":980.45,\"moveInDate\":\"2026-11-01\","
            + "\"maintenanceFeeAmount\":150000,\"maintenanceFeeItems\":\"수도·인터넷 포함\","
            + "\"occupancyStatus\":\"TENANT_OCCUPIED\",\"description\":\"역세권 풀옵션\","
            + "\"memo\":\"오후 연락\",\"referenceLeaseEndDate\":\"2027-02-28\","
            + "\"referenceLeaseEndSource\":\"임차인 구두 확인\"";
    long propertyId = Long.parseLong(extractId(cookie, draftBody(categoryId, extra)));
    mockMvc
        .perform(authedGet("/api/properties/" + propertyId, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationStage").value("DRAFT"))
        .andExpect(jsonPath("$.address").value("서울 강남구 테헤란로 123"))
        .andExpect(jsonPath("$.exclusiveAreaSqm").value(59.8))
        .andExpect(jsonPath("$.grossFloorAreaSqm").value(980.45))
        .andExpect(jsonPath("$.moveInNegotiable").value(false))
        .andExpect(jsonPath("$.occupancyStatus").value("TENANT_OCCUPIED"))
        .andExpect(jsonPath("$.managingMemberName").value(owner.user().getName()))
        .andExpect(jsonPath("$.referenceLeaseEndSource").value("임차인 구두 확인"))
        .andExpect(jsonPath("$.leaseEndDate").value("2027-02-28"))
        .andExpect(jsonPath("$.leaseEndSource").value("REFERENCE"));
  }

  private String extractId(Cookie cookie, String body) throws Exception {
    return String.valueOf(
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(jsonPostAs("/api/properties", body, cookie))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("propertyId")
            .longValue());
  }

  @Test
  void 필수값이_없거나_형식이_틀리면_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.AGENT));
    long categoryId = categoryIdOf("OFFICETEL");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/properties", "{\"categoryId\":" + categoryId + "}", cookie)),
        "name",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPostAs("/api/properties", "{\"name\":\"가\"}", cookie)),
        "categoryId",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPostAs("/api/properties", draftBody(categoryId, ",\"foo\":1"), cookie)),
        "foo",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties", draftBody(categoryId, ",\"exclusiveAreaSqm\":0"), cookie)),
        "exclusiveAreaSqm",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties", draftBody(categoryId, ",\"maintenanceFeeAmount\":-1"), cookie)),
        "maintenanceFeeAmount",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/properties", draftBody(categoryId, ",\"moveInDate\":\"내일\""), cookie)),
        "moveInDate",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties",
                draftBody(categoryId, ",\"occupancyStatus\":\"EMPTY\""),
                cookie)),
        "occupancyStatus",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties",
                draftBody(categoryId, ",\"referenceLeaseEndDate\":\"2027-01-01\""),
                cookie)),
        "referenceLeaseEndSource",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties",
                draftBody(categoryId, ",\"moveInNegotiable\":true,\"moveInDate\":\"2027-01-01\""),
                cookie)),
        "moveInDate",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties",
                "{\"name\":\"<script>\",\"categoryId\":" + categoryId + "}",
                cookie)),
        "name",
        "HTML_NOT_ALLOWED");
  }

  @Test
  void 없는_카테고리나_다른_사무소_카테고리는_404이고_비활성_카테고리는_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    assertError(
        mockMvc.perform(jsonPostAs("/api/properties", draftBody(999999, ""), cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    long foreign =
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/property-categories", "{\"name\":\"타 사무소\"}", cookieOf(other)))
                    .andReturn())
            .get("categoryId")
            .longValue();
    assertError(
        mockMvc.perform(jsonPostAs("/api/properties", draftBody(foreign, ""), cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    jdbcTemplate.update("UPDATE property_category SET active = false WHERE id = ?", foreign);
    assertError(
        mockMvc.perform(jsonPostAs("/api/properties", draftBody(foreign, ""), cookieOf(other))),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 담당자는_같은_사무소의_승인된_대표나_소속공인중개사여야_한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    var outsider = newStaff(OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long categoryId = categoryIdOf("APARTMENT");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties",
                draftBody(categoryId, ",\"managingMembershipId\":" + deputy.membership().getId()),
                cookie)),
        "managingMembershipId",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties",
                draftBody(categoryId, ",\"managingMembershipId\":" + outsider.membership().getId()),
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'SUSPENDED' WHERE id = ?",
        agent.membership().getId());
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties",
                draftBody(categoryId, ",\"managingMembershipId\":" + agent.membership().getId()),
                cookie)),
        "managingMembershipId",
        "NOT_ALLOWED");
  }

  @Test
  void 같은_주소와_호실이_있으면_사유가_있어야_별도_등록한다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long categoryId = categoryIdOf("APARTMENT");
    String address = ",\"address\":\"서울 강남구 역삼로 1\",\"addressDetail\":\"101동 1호\"";
    mockMvc
        .perform(jsonPostAs("/api/properties", draftBody(categoryId, address), cookie))
        .andExpect(status().isCreated());
    assertFieldError(
        mockMvc.perform(jsonPostAs("/api/properties", draftBody(categoryId, address), cookie)),
        "duplicateOverrideReason",
        "REQUIRED");
    mockMvc
        .perform(
            jsonPostAs(
                "/api/properties",
                draftBody(categoryId, address + ",\"duplicateOverrideReason\":\"권리 단위가 다름\""),
                cookie))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            jsonPostAs(
                "/api/properties",
                draftBody(
                    categoryId, ",\"address\":\"서울 강남구 역삼로 1\",\"addressDetail\":\"102동 1호\""),
                cookie))
        .andExpect(status().isCreated());
  }

  @Test
  void 유사_주소_조회는_같은_사무소의_보관되지_않은_매물만_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long categoryId = categoryIdOf("OFFICETEL");
    String address = ",\"address\":\"서울 강남구 역삼로 1\",\"addressDetail\":\"101호\"";
    String archivedId = extractId(cookie, draftBody(categoryId, address.replace("101호", "102호")));
    archive(Long.parseLong(archivedId));
    extractId(cookie, draftBody(categoryId, address));
    extractId(cookieOf(other), draftBody(categoryId, address));
    mockMvc
        .perform(
            authedGet("/api/properties/duplicate-check", cookie)
                .param("address", "서울  강남구 역삼로 1")
                .param("addressDetail", "101 호"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matches.length()").value(1))
        .andExpect(jsonPath("$.matches[0].categoryName").value("오피스텔"))
        .andExpect(jsonPath("$.matches[0].registrationStage").value("DRAFT"));
    mockMvc
        .perform(authedGet("/api/properties/duplicate-check", cookie).param("address", "없는 주소"))
        .andExpect(jsonPath("$.matches.length()").value(0));
    assertFieldError(
        mockMvc.perform(authedGet("/api/properties/duplicate-check", cookie)),
        "address",
        "REQUIRED");
  }

  @Test
  void 수정은_일부_필드만_바꾸고_버전을_올린다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long propertyId = createProperty(cookie, "원래 이름", categoryIdOf("OFFICETEL"));
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + propertyId,
                "{\"name\":\"바뀐 이름\",\"memo\":\"메모\",\"supplyAreaSqm\":84.2}",
                cookie,
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("바뀐 이름"))
        .andExpect(jsonPath("$.memo").value("메모"))
        .andExpect(jsonPath("$.supplyAreaSqm").value(84.2))
        .andExpect(jsonPath("$.registrationStage").value("DRAFT"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.photos.length()").value(0))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()));
    mockMvc
        .perform(jsonPatchAs("/api/properties/" + propertyId, "{\"memo\":null}", cookie, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memo").doesNotExist())
        .andExpect(jsonPath("$.name").value("바뀐 이름"))
        .andExpect(jsonPath("$.version").value(3));
  }

  @Test
  void 변경이_없으면_버전을_올리지_않는다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long propertyId = createProperty(cookie, "그대로", categoryIdOf("OFFICETEL"));
    mockMvc
        .perform(jsonPatchAs("/api/properties/" + propertyId, "{\"name\":\"그대로\"}", cookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void If_Match가_없거나_틀리면_각각_400과_409이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long propertyId = createProperty(cookie, "버전", categoryIdOf("OFFICETEL"));
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/properties/" + propertyId, "{\"name\":\"바뀜\"}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/properties/" + propertyId, "{\"name\":\"바뀜\"}", cookie, 7)),
        ErrorCode.VERSION_CONFLICT);
    mockMvc
        .perform(jsonPatchAs("/api/properties/" + propertyId, "{\"name\":\"바뀜\"}", cookie, 7))
        .andExpect(jsonPath("$.currentVersion").value(1));
  }

  @Test
  void 허용되지_않는_필드와_빈_본문은_400이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long propertyId = createProperty(cookie, "검증", categoryIdOf("OFFICETEL"));
    String url = "/api/properties/" + propertyId;
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":3}", cookie, 1)),
        "categoryId",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"registrationStage\":\"DRAFT\"}", cookie, 1)),
        "registrationStage",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"name\":null}", cookie, 1)), "name", "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"occupancyStatus\":null}", cookie, 1)),
        "occupancyStatus",
        "REQUIRED");
    assertError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, 1)), ErrorCode.INVALID_REQUEST);
    assertError(mockMvc.perform(jsonPatchAs(url, "[]", cookie, 1)), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 활성_전환은_주소와_담당자가_있어야_하고_성공하면_ACTIVE가_된다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long propertyId = createProperty(cookie, "전환", categoryIdOf("OFFICETEL"));
    String url = "/api/properties/" + propertyId;
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"registrationStage\":\"ACTIVE\"}", cookie, 1)),
        ErrorCode.INCOMPLETE_FOR_ACTIVATION);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                url, "{\"registrationStage\":\"ACTIVE\",\"address\":\"서울 어딘가\"}", cookie, 1)),
        ErrorCode.INCOMPLETE_FOR_ACTIVATION);
    assertThat(propertyVersion(propertyId)).isEqualTo(1);
    mockMvc
        .perform(
            jsonPatchAs(
                url,
                "{\"registrationStage\":\"ACTIVE\",\"address\":\"서울 어딘가\",\"managingMembershipId\":"
                    + owner.membership().getId()
                    + "}",
                cookie,
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationStage").value("ACTIVE"))
        .andExpect(jsonPath("$.managingMemberName").value(owner.user().getName()));
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"address\":null}", cookie, 2)),
        ErrorCode.INCOMPLETE_FOR_ACTIVATION);
  }

  @Test
  void 보관된_매물_수정은_409이고_다른_사무소_매물은_404이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    long propertyId = createProperty(cookieOf(owner), "보관", categoryIdOf("OFFICETEL"));
    String url = "/api/properties/" + propertyId;
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"name\":\"x\"}", cookieOf(other), 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/properties/999999", "{\"name\":\"x\"}", cookieOf(owner), 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    archive(propertyId);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"name\":\"x\"}", cookieOf(owner), 1)),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 수정에서_주소를_바꿔_같은_주소가_되면_사유를_요구하고_자기_자신은_제외한다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long categoryId = categoryIdOf("APARTMENT");
    String address = ",\"address\":\"서울 중구 세종대로 1\",\"addressDetail\":\"1호\"";
    long first = Long.parseLong(extractId(cookie, draftBody(categoryId, address)));
    long second = createProperty(cookie, "둘째", categoryId);
    assertFieldError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/properties/" + second,
                "{\"address\":\"서울 중구 세종대로 1\",\"addressDetail\":\"1호\"}",
                cookie,
                1)),
        "duplicateOverrideReason",
        "REQUIRED");
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + first,
                "{\"memo\":\"자기 자신\",\"address\":\"서울 중구 세종대로 1\"}",
                cookie,
                1))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + second,
                "{\"address\":\"서울 중구 세종대로 1\",\"addressDetail\":\"1호\","
                    + "\"duplicateOverrideReason\":\"다른 거래 단위\"}",
                cookie,
                1))
        .andExpect(status().isOk());
    assertThat(List.of(first, second)).hasSize(2);
  }
}
