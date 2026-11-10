package com.sairo.be.domain.property.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.property.service.PhotoSanitizerTest;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.PropertyIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class PropertyLifecycleIntegrationTest extends PropertyIntegrationTestBase {

  private long activeProperty(Cookie cookie, long managerId, long categoryId, String name)
      throws Exception {
    long id = createProperty(cookie, name, categoryId);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + id,
                "{\"registrationStage\":\"ACTIVE\",\"address\":\"서울 어딘가 1\",\"managingMembershipId\":"
                    + managerId
                    + "}",
                cookie,
                1))
        .andExpect(status().isOk());
    return id;
  }

  private long lifecycleCount(long propertyId, String type) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM entity_lifecycle_event WHERE property_id = ? AND event_type = ?",
            Long.class,
            propertyId,
            type);
    return count == null ? 0 : count;
  }

  @Test
  void 참조_없는_임시저장은_물리_삭제하고_사진_객체와_이력도_지운다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "삭제", categoryIdOf("OFFICETEL"));
    mockMvc
        .perform(
            photoUpload(
                "/api/properties/" + id + "/photos",
                cookie,
                PhotoSanitizerTest.minimalPng(),
                "image/png",
                null))
        .andExpect(status().isCreated());
    insertOffer(id, "SALE", "AVAILABLE", 1L, null, null);
    String key =
        jdbcTemplate.queryForObject(
            "SELECT object_key FROM property_photo WHERE property_id = ?", String.class, id);
    mockMvc.perform(deleteAs("/api/properties/" + id, cookie)).andExpect(status().isNoContent());
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM property", Long.class)).isZero();
    assertThat(
            jdbcTemplate.queryForObject("SELECT count(*) FROM entity_lifecycle_event", Long.class))
        .isZero();
    assertThat(objectStorage.find(key)).isEmpty();
    assertError(
        mockMvc.perform(authedGet("/api/properties/" + id, cookie)), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 참조가_있는_임시저장과_활성_매물은_보관하고_보관_이력을_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long referenced = createProperty(cookie, "참조", categoryIdOf("OFFICETEL"));
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "소유자");
    linkOwnerOccupant(owner.office().getId(), referenced, customer, "OWNER", "2024-01-01");
    mockMvc
        .perform(deleteAs("/api/properties/" + referenced, cookie))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet("/api/properties/" + referenced, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedAt").isString());
    assertThat(lifecycleCount(referenced, "ARCHIVED")).isEqualTo(1);
    long active =
        activeProperty(cookie, owner.membership().getId(), categoryIdOf("APARTMENT"), "활성");
    mockMvc
        .perform(deleteAs("/api/properties/" + active, cookie))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet("/api/properties", cookie).param("archived", "true"))
        .andExpect(jsonPath("$.totalElements").value(2));
    mockMvc
        .perform(authedGet("/api/properties", cookie))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertError(
        mockMvc.perform(deleteAs("/api/properties/" + active, cookie)),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 진행_중_조율이_있으면_409이고_종료된_조율은_막지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = activeProperty(cookie, owner.membership().getId(), categoryIdOf("APARTMENT"), "조율");
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "고객");
    long coordination =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,"
                + " period_start_date, period_end_date) VALUES (?, ?, ?, 'COORDINATABLE',"
                + " '2026-12-01', '2026-12-05') RETURNING id",
            Long.class,
            owner.office().getId(),
            customer,
            owner.membership().getId());
    jdbcTemplate.update(
        "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
            + " property_office_id, sort_order) VALUES (?, ?, ?, ?, 1)",
        coordination,
        owner.office().getId(),
        id,
        owner.office().getId());
    assertError(
        mockMvc.perform(deleteAs("/api/properties/" + id, cookie)),
        ErrorCode.PROPERTY_HAS_ACTIVE_COORDINATION);
    jdbcTemplate.update(
        "UPDATE visit_coordination SET status = 'VISIT_COMPLETED' WHERE id = ?", coordination);
    mockMvc.perform(deleteAs("/api/properties/" + id, cookie)).andExpect(status().isNoContent());
  }

  @Test
  void 체결_계약이_있으면_409이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = activeProperty(cookie, owner.membership().getId(), categoryIdOf("APARTMENT"), "계약");
    jdbcTemplate.update(
        "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id) VALUES"
            + " (?, ?, 'SALE', ?)",
        owner.office().getId(),
        id,
        owner.membership().getId());
    mockMvc.perform(deleteAs("/api/properties/" + id, cookie)).andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet("/api/properties/" + id, cookie))
        .andExpect(jsonPath("$.contracts.length()").value(1));
    long second =
        activeProperty(cookie, owner.membership().getId(), categoryIdOf("APARTMENT"), "계약2");
    jdbcTemplate.update(
        "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id,"
            + " contract_status, seller_landlord_name, buyer_tenant_name, signed_date,"
            + " balance_due_date, sale_price) VALUES (?, ?, 'SALE', ?, 'SIGNED', 'a', 'b',"
            + " '2026-01-01', '2026-02-01', 1)",
        owner.office().getId(),
        second,
        owner.membership().getId());
    assertError(
        mockMvc.perform(deleteAs("/api/properties/" + second, cookie)),
        ErrorCode.PROPERTY_HAS_ACTIVE_CONTRACT);
  }

  @Test
  void 보관하면_활성_신청_허용을_시스템이_회수하고_사건을_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var grantee = newStaff(OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long id = activeProperty(cookie, owner.membership().getId(), categoryIdOf("APARTMENT"), "허용");
    long approval =
        jdbcTemplate.queryForObject(
            "INSERT INTO property_access_approval (property_id, approver_type, approver_name,"
                + " approver_phone, valid_from, valid_until, source, retention_until) VALUES (?,"
                + " 'OWNER', '승인자', '010-1111-2222', now(), now() + interval '30 days',"
                + " 'DIRECT', current_date + 365) RETURNING id",
            Long.class,
            id);
    long active = insertGrant(approval, id, owner, grantee, false);
    long alreadyRevoked = insertGrant(approval, id, owner, grantee, true);
    mockMvc.perform(deleteAs("/api/properties/" + id, cookie)).andExpect(status().isNoContent());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT revoke_source, revoke_reason, revoked_at FROM visit_property_apply_grant WHERE id = ?",
            active);
    assertThat(row.get("revoke_source")).isEqualTo("SYSTEM");
    assertThat(row.get("revoke_reason")).isEqualTo("PROPERTY_ARCHIVED");
    assertThat(row.get("revoked_at")).isNotNull();
    List<Map<String, Object>> events =
        jdbcTemplate.queryForList(
            "SELECT grant_id, event_type, source, reason, affected_request_count FROM"
                + " visit_property_apply_grant_event");
    assertThat(events).hasSize(1);
    assertThat(events.get(0))
        .containsEntry("grant_id", active)
        .containsEntry("event_type", "REVOKED")
        .containsEntry("source", "SYSTEM")
        .containsEntry("affected_request_count", 0);
    assertThat(alreadyRevoked).isNotEqualTo(active);
  }

  private long insertGrant(
      long approval,
      long propertyId,
      com.sairo.be.support.fixture.TestData.Staff owner,
      com.sairo.be.support.fixture.TestData.Staff grantee,
      boolean revoked) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_property_apply_grant (approval_id, property_id, owner_office_id,"
                + " grantee_office_id, granted_by_membership_id"
                + (revoked
                    ? ", revoked_at, revoke_source, revoked_by_membership_id, revoke_reason"
                    : "")
                + ") VALUES (?, ?, ?, ?, ?"
                + (revoked ? ", now(), 'OFFICE', ?, '직접 회수'" : "")
                + ") RETURNING id",
            Long.class,
            revoked
                ? new Object[] {
                  approval,
                  propertyId,
                  owner.office().getId(),
                  grantee.office().getId(),
                  owner.membership().getId(),
                  owner.membership().getId()
                }
                : new Object[] {
                  approval,
                  propertyId,
                  owner.office().getId(),
                  grantee.office().getId(),
                  owner.membership().getId()
                });
    return id == null ? -1 : id;
  }

  @Test
  void 복원하면_주소와_담당자가_유효할_때_기존_단계를_유지한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = activeProperty(cookie, owner.membership().getId(), categoryIdOf("APARTMENT"), "복원");
    mockMvc.perform(deleteAs("/api/properties/" + id, cookie)).andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPostAs("/api/properties/" + id + "/restore", "{}", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationStage").value("ACTIVE"))
        .andExpect(jsonPath("$.deletedAt").doesNotExist())
        .andExpect(jsonPath("$.version").isNumber());
    assertThat(lifecycleCount(id, "RESTORED")).isEqualTo(1);
    mockMvc
        .perform(authedGet("/api/properties", cookie))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void 복원할_때_담당자가_더_이상_유효하지_않으면_임시저장으로_돌린다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long id = activeProperty(cookie, agent.membership().getId(), categoryIdOf("APARTMENT"), "강등");
    mockMvc.perform(deleteAs("/api/properties/" + id, cookie)).andExpect(status().isNoContent());
    jdbcTemplate.update(
        "UPDATE office_membership SET status = 'REVOKED', revoked_at = now(), revoked_by_type ="
            + " 'OWNER', revocation_reason = '퇴사' WHERE id = ?",
        agent.membership().getId());
    mockMvc
        .perform(jsonPostAs("/api/properties/" + id + "/restore", "{}", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registrationStage").value("DRAFT"));
  }

  @Test
  void 활성_매물_복원은_409이고_다른_사무소_매물은_404이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "활성", categoryIdOf("APARTMENT"));
    assertError(
        mockMvc.perform(jsonPostAs("/api/properties/" + id + "/restore", "{}", cookie)),
        ErrorCode.PROPERTY_NOT_ARCHIVED);
    archive(id);
    assertError(
        mockMvc.perform(jsonPostAs("/api/properties/" + id + "/restore", "{}", cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(deleteAs("/api/properties/" + id, cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 삭제와_복원은_If_Match를_보내면_검사한다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long id = createProperty(cookie, "버전", categoryIdOf("APARTMENT"));
    assertError(
        mockMvc.perform(deleteAs("/api/properties/" + id, cookie).header("If-Match", "9")),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(deleteAs("/api/properties/" + id, cookie).header("If-Match", "x")),
        ErrorCode.INVALID_REQUEST);
    archive(id);
    assertError(
        mockMvc.perform(
            jsonPostAs("/api/properties/" + id + "/restore", "{}", cookie).header("If-Match", "9")),
        ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void 카테고리를_바꾸면_이전_필드값을_보관하고_이력을_남기며_명시_입력으로만_되살린다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long office = categoryIdOf("OFFICETEL");
    long apartment = categoryIdOf("APARTMENT");
    long id = createProperty(cookie, "변경", office);
    long officeFloor = fieldIdOf("OFFICETEL", "해당층");
    long apartmentFloor = fieldIdOf("APARTMENT", "해당층");
    mockMvc
        .perform(
            jsonPutAs(
                "/api/properties/" + id + "/fields/" + officeFloor, "{\"value\":7}", cookie, 1))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + id + "/category",
                "{\"categoryId\":" + apartment + "}",
                cookie,
                2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.categoryId").value(apartment))
        .andExpect(jsonPath("$.categoryName").value("아파트"))
        .andExpect(jsonPath("$.customFields.length()").value(0))
        .andExpect(jsonPath("$.version").value(3));
    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT previous_category_id, new_category_id, recorded_by_membership_id FROM"
                + " entity_lifecycle_event WHERE event_type = 'CATEGORY_CHANGED'");
    assertThat(event)
        .containsEntry("previous_category_id", office)
        .containsEntry("new_category_id", apartment)
        .containsEntry("recorded_by_membership_id", owner.membership().getId());
    mockMvc
        .perform(authedGet("/api/properties/" + id, cookie))
        .andExpect(jsonPath("$.archivedCustomFields[0].value").value(7))
        .andExpect(jsonPath("$.archivedCustomFields[0].previousCategoryName").value("오피스텔"));
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + id + "/category",
                "{\"categoryId\":" + office + "}",
                cookie,
                3))
        .andExpect(jsonPath("$.customFields.length()").value(0));
    mockMvc
        .perform(
            jsonPutAs(
                "/api/properties/" + id + "/fields/" + officeFloor, "{\"value\":9}", cookie, 4))
        .andExpect(jsonPath("$.value").value(9));
    mockMvc
        .perform(authedGet("/api/properties/" + id, cookie))
        .andExpect(jsonPath("$.customFields[0].value").value(9))
        .andExpect(jsonPath("$.archivedCustomFields.length()").value(0));
    assertThat(apartmentFloor).isNotEqualTo(officeFloor);
  }

  @Test
  void 같은_카테고리로_바꾸면_아무것도_바꾸지_않고_현재_버전을_돌려준다() throws Exception {
    Cookie cookie = cookieOf(newStaff(OfficeMembershipRole.OWNER));
    long office = categoryIdOf("OFFICETEL");
    long id = createProperty(cookie, "동일", office);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/properties/" + id + "/category",
                "{\"categoryId\":" + office + "}",
                cookie,
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    assertThat(lifecycleCount(id, "CATEGORY_CHANGED")).isZero();
  }

  @Test
  void 카테고리_변경의_잘못된_요청은_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createProperty(cookie, "오류", categoryIdOf("OFFICETEL"));
    String url = "/api/properties/" + id + "/category";
    assertFieldError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, 1)), "categoryId", "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":\"3\"}", cookie, 1)),
        "categoryId",
        "TYPE_MISMATCH");
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":999999}", cookie, 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":3}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":3}", cookie, 5)),
        ErrorCode.VERSION_CONFLICT);
    long inactive =
        com.sairo.be.support.AuthFlowSupport.json(
                mockMvc
                    .perform(jsonPostAs("/api/property-categories", "{\"name\":\"꺼짐\"}", cookie))
                    .andReturn())
            .get("categoryId")
            .longValue();
    jdbcTemplate.update("UPDATE property_category SET active = false WHERE id = ?", inactive);
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":" + inactive + "}", cookie, 1)),
        "categoryId",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":3}", cookieOf(other), 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    archive(id);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"categoryId\":3}", cookie, 1)),
        ErrorCode.PROPERTY_ARCHIVED);
  }
}
