package com.sairo.be.domain.property.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.PropertyIntegrationTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PropertyQueryIntegrationTest extends PropertyIntegrationTestBase {

  private long register(Cookie cookie, String body) throws Exception {
    return com.sairo.be.support.AuthFlowSupport.json(
            mockMvc.perform(jsonPostAs("/api/properties", body, cookie)).andReturn())
        .get("propertyId")
        .longValue();
  }

  @Test
  void 목록은_사무소_안에서_수정시각_역순으로_페이지를_나눈다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long categoryId = categoryIdOf("APARTMENT");
    createProperty(cookie, "첫째", categoryId);
    createProperty(cookie, "둘째", categoryId);
    createProperty(cookieOf(other), "타 사무소", categoryId);
    mockMvc
        .perform(authedGet("/api/properties", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.items[0].name").value("둘째"))
        .andExpect(jsonPath("$.items[1].name").value("첫째"))
        .andExpect(jsonPath("$.items[0].officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.items[0].version").value(1))
        .andExpect(jsonPath("$.items[0].categoryName").value("아파트"))
        .andExpect(jsonPath("$.items[0].ownerNames.length()").value(0))
        .andExpect(jsonPath("$.items[0].activeOfferSummary.length()").value(0));
    mockMvc
        .perform(authedGet("/api/properties", cookie).param("size", "50").param("page", "1"))
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(50));
    assertFieldError(
        mockMvc.perform(authedGet("/api/properties", cookie).param("size", "10")),
        "size",
        "NOT_ALLOWED");
  }

  @Test
  void 키워드는_매물명_주소_연결_고객명과_병합된_고객의_원래_이름을_찾는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long categoryId = categoryIdOf("APARTMENT");
    long byName = createProperty(cookie, "역삼 래미안", categoryId);
    long byAddress =
        register(
            cookie,
            "{\"name\":\"주소 매물\",\"categoryId\":" + categoryId + ",\"address\":\"서울 서초구 반포대로 9\"}");
    long byCustomer = createProperty(cookie, "고객 연결", categoryId);
    long byMerged = createProperty(cookie, "병합 연결", categoryId);
    long hong = insertCustomer(owner.office().getId(), owner.membership().getId(), "홍길동");
    long primary = insertCustomer(owner.office().getId(), owner.membership().getId(), "김주인");
    long merged = insertCustomer(owner.office().getId(), owner.membership().getId(), "박이전");
    linkOwnerOccupant(owner.office().getId(), byCustomer, hong, "OWNER", "2024-01-01");
    linkOwnerOccupant(owner.office().getId(), byMerged, primary, "OWNER", "2024-01-01");
    jdbcTemplate.update(
        "INSERT INTO customer_merge_log (primary_customer_id, merged_customer_id,"
            + " executed_by_membership_id) VALUES (?, ?, ?)",
        primary,
        merged,
        owner.membership().getId());
    assertIds(cookie, "래미안", byName);
    assertIds(cookie, "반포대로", byAddress);
    assertIds(cookie, "홍길", byCustomer);
    assertIds(cookie, "박이전", byMerged);
    assertIds(cookie, "100%_없음", new long[0]);
  }

  private void assertIds(Cookie cookie, String keyword, long... expected) throws Exception {
    var result =
        com.sairo.be.support.AuthFlowSupport.json(
            mockMvc
                .perform(authedGet("/api/properties", cookie).param("keyword", keyword))
                .andReturn());
    java.util.List<Long> actual = new java.util.ArrayList<>();
    result.get("items").forEach(item -> actual.add(item.get("propertyId").longValue()));
    org.assertj.core.api.Assertions.assertThat(actual)
        .containsExactlyInAnyOrderElementsOf(java.util.Arrays.stream(expected).boxed().toList());
  }

  @Test
  void 카테고리_담당자_등록단계_보관_필터와_거래유형별_가격_면적_범위를_적용한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long apartment = categoryIdOf("APARTMENT");
    long office = categoryIdOf("OFFICETEL");
    long a =
        register(
            cookie,
            "{\"name\":\"A\",\"categoryId\":"
                + apartment
                + ",\"exclusiveAreaSqm\":59.8,\"managingMembershipId\":"
                + agent.membership().getId()
                + "}");
    long b =
        register(cookie, "{\"name\":\"B\",\"categoryId\":" + office + ",\"exclusiveAreaSqm\":84}");
    long c = createProperty(cookie, "C", apartment);
    insertOffer(a, "MONTHLY", "AVAILABLE", null, 20_000_000L, 900_000L);
    insertOffer(b, "SALE", "NEGOTIATING", 500_000_000L, null, null);
    insertOffer(c, "JEONSE", "COMPLETED", null, 300_000_000L, null);
    archive(c);
    jdbcTemplate.update(
        "UPDATE property SET registration_stage = 'ACTIVE', address = 'x', managing_membership_id = ?"
            + " WHERE id = ?",
        owner.membership().getId(),
        b);

    assertOnly(cookie, a, "categoryId", String.valueOf(apartment));
    assertOnly(cookie, a, "managingMembershipId", String.valueOf(agent.membership().getId()));
    assertOnly(cookie, b, "registrationStage", "ACTIVE");
    assertOnly(cookie, c, "archived", "true");
    assertOnly(cookie, b, "dealType", "SALE");
    assertOnly(cookie, a, "dealType", "MONTHLY");
    mockMvc
        .perform(authedGet("/api/properties", cookie).param("dealType", "JEONSE"))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertOnly(cookie, b, "dealType", "SALE", "priceMin", "400000000", "priceMax", "600000000");
    mockMvc
        .perform(
            authedGet("/api/properties", cookie)
                .param("dealType", "SALE")
                .param("priceMax", "100000000"))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertOnly(cookie, a, "areaType", "EXCLUSIVE", "areaMax", "60");
    assertOnly(cookie, b, "areaType", "EXCLUSIVE", "areaMin", "80");
    mockMvc
        .perform(
            authedGet("/api/properties", cookie)
                .param("registrationStage", "ACTIVE")
                .param("archived", "true"))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  private void assertOnly(Cookie cookie, long expected, String... params) throws Exception {
    var request = authedGet("/api/properties", cookie);
    for (int i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }
    mockMvc
        .perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].propertyId").value(expected));
  }

  @Test
  void 목록에_소유자_점유자_활성_제안과_만기일_출처를_담는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id =
        register(
            cookie,
            "{\"name\":\"만기\",\"categoryId\":"
                + categoryIdOf("OFFICETEL")
                + ",\"referenceLeaseEndDate\":\"2027-02-28\",\"referenceLeaseEndSource\":\"구두\"}");
    long ownerCustomer = insertCustomer(owner.office().getId(), owner.membership().getId(), "소유자");
    long occupant = insertCustomer(owner.office().getId(), owner.membership().getId(), "점유자");
    long expired = insertCustomer(owner.office().getId(), owner.membership().getId(), "종료됨");
    linkOwnerOccupant(owner.office().getId(), id, ownerCustomer, "OWNER", "2024-01-01");
    linkOwnerOccupant(owner.office().getId(), id, occupant, "OCCUPANT", "2025-01-01");
    jdbcTemplate.update(
        "INSERT INTO property_owner_occupant (office_id, property_id, customer_id, role, start_date,"
            + " end_date) VALUES (?, ?, ?, 'OCCUPANT', '2020-01-01', '2021-01-01')",
        owner.office().getId(),
        id,
        expired);
    insertOffer(id, "MONTHLY", "AVAILABLE", null, 1000L, 50L);
    insertOffer(id, "SALE", "COMPLETED", 9L, null, null);
    mockMvc
        .perform(authedGet("/api/properties", cookie))
        .andExpect(jsonPath("$.items[0].ownerNames[0]").value("소유자"))
        .andExpect(jsonPath("$.items[0].occupantNames.length()").value(1))
        .andExpect(jsonPath("$.items[0].occupantNames[0]").value("점유자"))
        .andExpect(jsonPath("$.items[0].activeOfferSummary.length()").value(1))
        .andExpect(jsonPath("$.items[0].activeOfferSummary[0].dealType").value("MONTHLY"))
        .andExpect(jsonPath("$.items[0].activeOfferSummary[0].depositAmount").value(1000))
        .andExpect(jsonPath("$.items[0].leaseEndDate").value("2027-02-28"))
        .andExpect(jsonPath("$.items[0].leaseEndSource").value("REFERENCE"));
    jdbcTemplate.update(
        "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id) VALUES"
            + " (?, ?, 'MONTHLY', ?)",
        owner.office().getId(),
        id,
        owner.membership().getId());
    jdbcTemplate.update(
        "UPDATE contract SET contract_status = 'SIGNED', seller_landlord_name = 'a',"
            + " buyer_tenant_name = 'b', signed_date = '2026-01-01', balance_due_date ="
            + " '2026-02-01', deposit_amount = 1, monthly_rent_amount = 1, lease_start_date ="
            + " '2026-03-01', lease_end_date = '2028-03-01' WHERE property_id = ?",
        id);
    mockMvc
        .perform(authedGet("/api/properties", cookie))
        .andExpect(jsonPath("$.items[0].leaseEndDate").value("2028-03-01"))
        .andExpect(jsonPath("$.items[0].leaseEndSource").value("CONTRACT"));
  }

  @Test
  void 목록_조건이_맞지_않으면_400이고_범위가_뒤집히면_INVALID_RANGE이다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    assertFieldError(
        mockMvc.perform(authedGet("/api/properties", cookie).param("areaMin", "10")),
        "areaType",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/properties", cookie).param("priceMin", "10")),
        "dealType",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/properties", cookie).param("dealType", "RENT")),
        "dealType",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/properties", cookie).param("categoryId", "abc")),
        "categoryId",
        "TYPE_MISMATCH");
    assertError(
        mockMvc.perform(
            authedGet("/api/properties", cookie)
                .param("dealType", "SALE")
                .param("priceMin", "10")
                .param("priceMax", "5")),
        ErrorCode.INVALID_RANGE);
    assertError(
        mockMvc.perform(
            authedGet("/api/properties", cookie)
                .param("areaType", "LAND")
                .param("areaMin", "10")
                .param("areaMax", "5")),
        ErrorCode.INVALID_RANGE);
  }

  @Test
  void 상세는_사진_커스텀필드_거래제안과_연결된_방문_계약_조율_소유관계를_담는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();
    long membershipId = owner.membership().getId();
    long id = createProperty(cookie, "상세", categoryIdOf("OFFICETEL"));
    long customer = insertCustomer(officeId, membershipId, "이수요");
    long landlord = insertCustomer(officeId, membershipId, "김휴이");
    long linkId = linkOwnerOccupant(officeId, id, landlord, "OWNER", "2024-01-01");
    insertOffer(id, "MONTHLY", "AVAILABLE", null, 20_000_000L, 900_000L);
    long floor = fieldIdOf("OFFICETEL", "해당층");
    mockMvc
        .perform(
            jsonPutAs("/api/properties/" + id + "/fields/" + floor, "{\"value\":12}", cookie, 1))
        .andExpect(status().isOk());
    byte[] png = com.sairo.be.domain.property.service.PhotoSanitizerTest.minimalPng();
    mockMvc
        .perform(photoUpload("/api/properties/" + id + "/photos", cookie, png, "image/png", "거실"))
        .andExpect(status().isCreated());
    long coordination = insertCoordination(officeId, customer, membershipId, "CONFIRMED");
    long candidate = insertCandidate(coordination, officeId, id);
    insertScheduleItem(coordination, officeId, candidate, "COMPLETED");
    insertScheduleItem(coordination, officeId, candidate, "SCHEDULED");
    jdbcTemplate.update(
        "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id) VALUES"
            + " (?, ?, 'JEONSE', ?)",
        officeId,
        id,
        membershipId);
    mockMvc
        .perform(authedGet("/api/properties/" + id, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.propertyId").value(id))
        .andExpect(jsonPath("$.categoryName").value("오피스텔"))
        .andExpect(jsonPath("$.photos.length()").value(1))
        .andExpect(jsonPath("$.photos[0].description").value("거실"))
        .andExpect(jsonPath("$.photos[0].url").isString())
        .andExpect(jsonPath("$.photos[0].urlExpiresAt").isString())
        .andExpect(jsonPath("$.customFields[0].fieldName").value("해당층"))
        .andExpect(jsonPath("$.customFields[0].fieldType").value("NUMBER"))
        .andExpect(jsonPath("$.customFields[0].value").value(12))
        .andExpect(jsonPath("$.offers[0].dealType").value("MONTHLY"))
        .andExpect(jsonPath("$.offers[0].depositAmount").value(20000000))
        .andExpect(jsonPath("$.offers[0].salePrice").doesNotExist())
        .andExpect(jsonPath("$.ownerOccupants[0].linkId").value(linkId))
        .andExpect(jsonPath("$.ownerOccupants[0].customerName").value("김휴이"))
        .andExpect(jsonPath("$.activeCoordinations[0].status").value("CONFIRMED"))
        .andExpect(jsonPath("$.activeCoordinations[0].customerName").value("이수요"))
        .andExpect(jsonPath("$.visits.length()").value(1))
        .andExpect(jsonPath("$.visits[0].result").value("COMPLETED"))
        .andExpect(jsonPath("$.contracts[0].contractStatus").value("DRAFT"))
        .andExpect(jsonPath("$.archivedCustomFields.length()").value(0))
        .andExpect(jsonPath("$.deletedAt").doesNotExist())
        .andExpect(jsonPath("$.officeId").value(officeId))
        .andExpect(jsonPath("$.version").value(3));
  }

  @Test
  void 상세는_보관된_매물도_돌려주고_보관된_필드값은_이전_카테고리와_함께_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "보관 필드", categoryIdOf("OFFICETEL"));
    long floor = fieldIdOf("OFFICETEL", "해당층");
    mockMvc
        .perform(
            jsonPutAs("/api/properties/" + id + "/fields/" + floor, "{\"value\":7}", cookie, 1))
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE property_field_value SET is_archived = true WHERE property_id = ?", id);
    archive(id);
    mockMvc
        .perform(authedGet("/api/properties/" + id, cookie))
        .andExpect(jsonPath("$.customFields.length()").value(0))
        .andExpect(jsonPath("$.archivedCustomFields[0].value").value(7))
        .andExpect(jsonPath("$.archivedCustomFields[0].previousCategoryName").value("오피스텔"))
        .andExpect(jsonPath("$.deletedAt").isString());
  }

  @Test
  void 상세는_다른_사무소_매물과_없는_매물에_404이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    long id = createProperty(cookieOf(owner), "비공개", categoryIdOf("OFFICETEL"));
    assertError(
        mockMvc.perform(authedGet("/api/properties/" + id, cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet("/api/properties/999999", cookieOf(owner))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 다른_사무소의_방문과_조율은_상세에_보이지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "격리", categoryIdOf("OFFICETEL"));
    long otherCustomer =
        insertCustomer(other.office().getId(), other.membership().getId(), "타사무소고객");
    long coordination =
        insertCoordination(
            other.office().getId(), otherCustomer, other.membership().getId(), "CONFIRMED");
    jdbcTemplate.update(
        "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
            + " property_office_id, sort_order) VALUES (?, ?, ?, ?, 1)",
        coordination,
        other.office().getId(),
        id,
        owner.office().getId());
    mockMvc
        .perform(authedGet("/api/properties/" + id, cookie))
        .andExpect(jsonPath("$.activeCoordinations.length()").value(0))
        .andExpect(jsonPath("$.visits.length()").value(0));
  }

  protected long insertCoordination(
      long officeId, long customerId, long membershipId, String status) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,"
                + " period_start_date, period_end_date) VALUES (?, ?, ?, ?, '2026-12-01',"
                + " '2026-12-05') RETURNING id",
            Long.class,
            officeId,
            customerId,
            membershipId,
            status);
    return id == null ? -1 : id;
  }

  protected long insertCandidate(long coordinationId, long officeId, long propertyId) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
                + " property_office_id, sort_order) VALUES (?, ?, ?, ?, 1) RETURNING id",
            Long.class,
            coordinationId,
            officeId,
            propertyId,
            officeId);
    return id == null ? -1 : id;
  }

  protected void insertScheduleItem(
      long coordinationId, long officeId, long candidateId, String result) {
    jdbcTemplate.update(
        "INSERT INTO visit_schedule_item (coordination_id, office_id, candidate_property_id,"
            + " scheduled_start_at, scheduled_end_at, sort_order, result, actual_visited_at) VALUES"
            + " (?, ?, ?, '2026-12-02 10:00+09', '2026-12-02 10:30+09', 1, ?, CASE WHEN ? ="
            + " 'COMPLETED' THEN '2026-12-02 10:03+09'::timestamptz END)",
        coordinationId,
        officeId,
        candidateId,
        result,
        result);
  }

  @Test
  void 보관_필터에서_TestData_사무소_격리를_유지한다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff other = newStaff(OfficeMembershipRole.OWNER);
    long id = createProperty(cookieOf(other), "타사", categoryIdOf("APARTMENT"));
    archive(id);
    mockMvc
        .perform(authedGet("/api/properties", cookieOf(owner)).param("archived", "true"))
        .andExpect(jsonPath("$.totalElements").value(0));
  }
}
