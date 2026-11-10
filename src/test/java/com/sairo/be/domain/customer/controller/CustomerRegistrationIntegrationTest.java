package com.sairo.be.domain.customer.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.CustomerIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;

@IntegrationTest
class CustomerRegistrationIntegrationTest extends CustomerIntegrationTestBase {

  @Test
  void 고객을_등록하면_수집_근거를_저장하고_생성_사건을_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    mockMvc
        .perform(
            jsonPostAs(
                "/api/customers",
                customerBody(
                    "김휴이", "01012345678", owner.membership().getId(), ",\"email\":\"a@b.co\""),
                cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.processingBasis").value("CONSENT"))
        .andExpect(jsonPath("$.deletedAt").doesNotExist());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT phone, email, data_source, data_purpose, retention_until::text AS retention,"
                + " processing_basis FROM customer");
    assertThat(row)
        .containsEntry("phone", "010-1234-5678")
        .containsEntry("data_source", "방문상담")
        .containsEntry("retention", "2030-01-01");
    Map<String, Object> event =
        jdbcTemplate.queryForMap(
            "SELECT e.event_type, e.entity_type, e.effective_at = c.created_at AS same FROM"
                + " entity_lifecycle_event e JOIN customer c ON c.id = e.customer_id");
    assertThat(event)
        .containsEntry("event_type", "CREATED")
        .containsEntry("entity_type", "CUSTOMER")
        .containsEntry("same", true);
  }

  @Test
  void 같은_이름과_전화번호가_있어도_자동_병합하지_않고_신규_등록한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    createCustomer(cookie, "중복", "010-1111-2222", owner.membership().getId());
    createCustomer(cookie, "중복", "010-1111-2222", owner.membership().getId());
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customer", Long.class))
        .isEqualTo(2);
  }

  @Test
  void 필수값이_없거나_형식이_틀리면_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
    fields.put("isCorporation", "false");
    fields.put("name", "\"가\"");
    fields.put("managingMembershipId", String.valueOf(m));
    fields.put("dataSource", "\"방문상담\"");
    fields.put("processingBasis", "\"CONSENT\"");
    fields.put("dataPurpose", "\"상담\"");
    fields.put("retentionUntil", "\"2030-01-01\"");
    for (String missing : fields.keySet()) {
      StringBuilder body = new StringBuilder("{");
      fields.forEach(
          (key, value) -> {
            if (!key.equals(missing)) {
              body.append(body.length() > 1 ? "," : "")
                  .append("\"")
                  .append(key)
                  .append("\":")
                  .append(value);
            }
          });
      body.append("}");
      assertFieldError(
          mockMvc.perform(jsonPostAs("/api/customers", body.toString(), cookie)),
          missing,
          "REQUIRED");
    }
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/customers", customerBody("가", null, m, ",\"foo\":1"), cookie)),
        "foo",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers",
                customerBody("가", null, m, "").replace("CONSENT", "UNSPECIFIED"),
                cookie)),
        "processingBasis",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/customers", customerBody("가", null, m, ",\"email\":\"abc\""), cookie)),
        "email",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers",
                customerBody("가", null, m, "").replace("2030-01-01", "내일"),
                cookie)),
        "retentionUntil",
        "INVALID_FORMAT");
  }

  @Test
  void 주민등록번호나_계좌_패턴이_감지되면_400_SENSITIVE_DATA_DETECTED이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    assertError(
        mockMvc.perform(
            jsonPostAs("/api/customers", customerBody("홍길동 900101-1234567", null, m, ""), cookie)),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers",
                customerBody("가", null, m, ",\"memo\":\"계좌 110-123-456789 입금\""),
                cookie)),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    mockMvc
        .perform(
            jsonPostAs(
                "/api/customers",
                customerBody("정상", "010-1234-5678", m, ",\"memo\":\"전화 02-123-4567\""),
                cookie))
        .andExpect(status().isCreated());
  }

  @Test
  void 담당자는_같은_사무소의_승인된_대표나_소속공인중개사여야_한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    var outsider = newStaff(OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers",
                customerBody("가", null, deputy.membership().getId(), ""),
                cookie)),
        "managingMembershipId",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/customers",
                customerBody("가", null, outsider.membership().getId(), ""),
                cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 수정은_일부_필드만_바꾸고_버전을_올리며_null은_값을_비운다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createCustomer(cookie, "김휴이", "010-1111-2222", owner.membership().getId());
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/customers/" + id,
                "{\"memo\":\"재연락\",\"secondaryPhone\":\"010-9999-8888\",\"processingBasis\":\"CONTRACT_PERFORMANCE\"}",
                cookie,
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memo").value("재연락"))
        .andExpect(jsonPath("$.secondaryPhone").value("010-9999-8888"))
        .andExpect(jsonPath("$.processingBasis").value("CONTRACT_PERFORMANCE"))
        .andExpect(jsonPath("$.phone").value("010-1111-2222"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mockMvc
        .perform(jsonPatchAs("/api/customers/" + id, "{\"phone\":null}", cookie, 2))
        .andExpect(jsonPath("$.phone").doesNotExist())
        .andExpect(jsonPath("$.memo").value("재연락"))
        .andExpect(jsonPath("$.version").value(3));
  }

  @Test
  void 수정의_잘못된_요청은_정해진_오류를_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createCustomer(cookie, "검증", "010-1111-2222", owner.membership().getId());
    String url = "/api/customers/" + id;
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"memo\":\"x\"}", cookie, null)),
        ErrorCode.INVALID_REQUEST);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"memo\":\"x\"}", cookie, 5)),
        ErrorCode.VERSION_CONFLICT);
    assertError(mockMvc.perform(jsonPatchAs(url, "{}", cookie, 1)), ErrorCode.INVALID_REQUEST);
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"name\":null}", cookie, 1)), "name", "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"isCorporation\":true}", cookie, 1)),
        "isCorporation",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"memo\":\"900101-1234567\"}", cookie, 1)),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"memo\":\"x\"}", cookieOf(other), 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPatchAs("/api/customers/99999", "{\"memo\":\"x\"}", cookie, 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", id);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"memo\":\"x\"}", cookie, 1)),
        ErrorCode.CUSTOMER_ARCHIVED);
  }

  @Test
  void 참조_없는_고객은_물리_삭제하고_참조가_있으면_보관한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long clean = createCustomer(cookie, "오입력", null, owner.membership().getId());
    mockMvc.perform(deleteAs("/api/customers/" + clean, cookie)).andExpect(status().isNoContent());
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customer", Long.class)).isZero();
    assertThat(
            jdbcTemplate.queryForObject("SELECT count(*) FROM entity_lifecycle_event", Long.class))
        .isZero();
    long referenced = createCustomer(cookie, "상담있음", null, owner.membership().getId());
    createConsultationRow(referenced, owner.membership().getId(), "FIRST_INQUIRY");
    mockMvc
        .perform(authedGet("/api/customers/" + referenced, cookie))
        .andExpect(jsonPath("$.deletable").value(false));
    mockMvc
        .perform(deleteAs("/api/customers/" + referenced, cookie))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet("/api/customers/" + referenced, cookie))
        .andExpect(jsonPath("$.status").value("ARCHIVED"))
        .andExpect(jsonPath("$.deletedAt").isString());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_lifecycle_event WHERE event_type = 'ARCHIVED'",
                Long.class))
        .isEqualTo(1);
    assertError(
        mockMvc.perform(deleteAs("/api/customers/" + referenced, cookie)),
        ErrorCode.CUSTOMER_ARCHIVED);
  }

  @Test
  void 보관_고객을_복원하고_보관이_아니면_409이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createCustomer(cookie, "복원", null, owner.membership().getId());
    assertError(
        mockMvc.perform(jsonPostAs("/api/customers/" + id + "/restore", "{}", cookie)),
        ErrorCode.CUSTOMER_NOT_ARCHIVED);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", id);
    assertError(
        mockMvc.perform(jsonPostAs("/api/customers/" + id + "/restore", "{}", cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
    mockMvc
        .perform(jsonPostAs("/api/customers/" + id + "/restore", "{}", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.deletedAt").doesNotExist());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM entity_lifecycle_event WHERE event_type = 'RESTORED'",
                Long.class))
        .isEqualTo(1);
    assertError(
        mockMvc.perform(
            jsonPostAs("/api/customers/" + id + "/restore", "{}", cookie).header("If-Match", "9")),
        ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void 삭제와_복원은_If_Match를_보내면_검사한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createCustomer(cookie, "버전", null, owner.membership().getId());
    assertError(
        mockMvc.perform(deleteAs("/api/customers/" + id, cookie).header("If-Match", "9")),
        ErrorCode.VERSION_CONFLICT);
  }

  @Test
  void 병합된_고객은_수정_삭제_복원이_409_INVALID_TRANSITION이고_상세는_MERGED이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long a = createCustomer(cookie, "남는고객", "010-1111-1111", owner.membership().getId());
    long b = createCustomer(cookie, "합쳐진고객", "010-2222-2222", owner.membership().getId());
    long c = createCustomer(cookie, "최종고객", "010-3333-3333", owner.membership().getId());
    mergeRow(a, b, owner.membership().getId());
    mergeRow(c, a, owner.membership().getId());
    mockMvc
        .perform(authedGet("/api/customers/" + b, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("MERGED"))
        .andExpect(jsonPath("$.mergedIntoCustomerId").value(c))
        .andExpect(jsonPath("$.name").value("합쳐진고객"))
        .andExpect(jsonPath("$.deletable").value(false))
        .andExpect(jsonPath("$.consultations.length()").value(0));
    assertError(
        mockMvc.perform(jsonPatchAs("/api/customers/" + b, "{\"memo\":\"x\"}", cookie, 1)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(deleteAs("/api/customers/" + b, cookie)), ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPostAs("/api/customers/" + b + "/restore", "{}", cookie)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 같은_이름_전화번호_후보는_보관_고객을_포함하고_병합된_고객은_최종_고객으로_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long first = createCustomer(cookie, "김 휴이", "010-1234-5678", m);
    long archived = createCustomer(cookie, "김휴이", "01012345678", m);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", archived);
    createCustomer(cookieOf(other), "김휴이", "010-1234-5678", other.membership().getId());
    long mergedAway = createCustomer(cookie, "박이전", "010-7777-8888", m);
    long keep = createCustomer(cookie, "박최종", "010-4444-5555", m);
    mergeRow(keep, mergedAway, m);
    mockMvc
        .perform(
            authedGet("/api/customers/duplicate-candidates", cookie)
                .param("name", "김휴이")
                .param("phone", "010-1234-5678"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].customerId").value(first))
        .andExpect(jsonPath("$.items[1].deletedAt").isString())
        .andExpect(jsonPath("$.items[0].roles[0]").value("OTHER"));
    mockMvc
        .perform(
            authedGet("/api/customers/duplicate-candidates", cookie)
                .param("name", "박이전")
                .param("phone", "01077778888"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].customerId").value(keep));
    assertFieldError(
        mockMvc.perform(
            authedGet("/api/customers/duplicate-candidates", cookie).param("name", "가")),
        "phone",
        "REQUIRED");
  }

  @Test
  void 목록은_병합된_고객을_빼고_원래_이름_연락처로도_검색하고_역할과_보관을_거른다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long a = createCustomer(cookie, "남는고객", "010-1111-1111", m);
    long b = createCustomer(cookie, "합쳐진고객", "010-2222-2222", m);
    long demand = createCustomer(cookie, "수요고객", "010-3333-3333", m);
    long archived = createCustomer(cookie, "보관고객", "010-4444-4444", m);
    createCustomer(cookieOf(other), "타사무소", "010-5555-5555", other.membership().getId());
    mergeRow(a, b, m);
    jdbcTemplate.update(
        "INSERT INTO customer_demand_condition (customer_id, deal_type) VALUES (?, 'SALE')",
        demand);
    jdbcTemplate.update("UPDATE customer SET deleted_at = now() WHERE id = ?", archived);
    mockMvc
        .perform(authedGet("/api/customers", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.items[0].version").value(1));
    mockMvc
        .perform(authedGet("/api/customers", cookie).param("keyword", "합쳐진"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].customerId").value(a));
    mockMvc
        .perform(authedGet("/api/customers", cookie).param("keyword", "2222"))
        .andExpect(jsonPath("$.items[0].customerId").value(a));
    mockMvc
        .perform(authedGet("/api/customers", cookie).param("role", "DEMAND"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].roles[0]").value("DEMAND"));
    mockMvc
        .perform(authedGet("/api/customers", cookie).param("role", "OTHER"))
        .andExpect(jsonPath("$.totalElements").value(1));
    mockMvc
        .perform(authedGet("/api/customers", cookie).param("archived", "true"))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].customerId").value(archived));
    assertFieldError(
        mockMvc.perform(authedGet("/api/customers", cookie).param("role", "X")),
        "role",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/customers", cookie).param("size", "7")),
        "size",
        "NOT_ALLOWED");
  }

  @Test
  void 상세는_연결_기록을_병합_집합까지_모아_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long officeId = owner.office().getId();
    long a = createCustomer(cookie, "남는고객", "010-1111-1111", m);
    long b = createCustomer(cookie, "합쳐진고객", "010-2222-2222", m);
    mergeRow(a, b, m);
    long property = createProperty(cookie, "연결 매물", categoryIdOf("OFFICETEL"));
    linkOwnerOccupant(officeId, property, b, "OWNER", "2024-01-01");
    jdbcTemplate.update(
        "UPDATE property_owner_occupant SET confirmed_at = now(), confirmed_by_membership_id = ?",
        m);
    createConsultationRow(b, m, "FIRST_INQUIRY");
    jdbcTemplate.update(
        "INSERT INTO customer_demand_condition (customer_id, deal_type) VALUES (?, 'MONTHLY')", b);
    jdbcTemplate.update(
        "INSERT INTO task (office_id, title, assignee_membership_id, due_date, related_customer_id)"
            + " VALUES (?, '재안내', ?, current_date, ?)",
        officeId,
        m,
        b);
    mockMvc
        .perform(authedGet("/api/customers/" + a, cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.mergedIntoCustomerId").doesNotExist())
        .andExpect(jsonPath("$.roles.length()").value(2))
        .andExpect(jsonPath("$.relatedProperties[0].role").value("OWNER"))
        .andExpect(jsonPath("$.relatedProperties[0].propertyName").value("연결 매물"))
        .andExpect(jsonPath("$.currentOwnedOccupied[0].linkId").isNumber())
        .andExpect(jsonPath("$.consultations[0].consultationType").value("FIRST_INQUIRY"))
        .andExpect(jsonPath("$.consultations[0].authorName").value(owner.user().getName()))
        .andExpect(jsonPath("$.consultations[0].revisionCount").value(0))
        .andExpect(jsonPath("$.demandConditions[0].dealType").value("MONTHLY"))
        .andExpect(jsonPath("$.tasks[0].title").value("재안내"))
        .andExpect(jsonPath("$.deletable").value(false))
        .andExpect(jsonPath("$.processingBasis").value("CONSENT"));
  }

  @Test
  void 상세는_계약_당사자_방문_조율과_예정_과거_계약을_나눠_돌려준다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long m = owner.membership().getId();
    long officeId = owner.office().getId();
    long customer = createCustomer(cookie, "당사자", "010-1111-1111", m);
    long property = createProperty(cookie, "계약 매물", categoryIdOf("OFFICETEL"));
    long open =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id) VALUES"
                + " (?, ?, 'SALE', ?) RETURNING id",
            Long.class,
            officeId,
            property,
            m);
    long ended =
        jdbcTemplate.queryForObject(
            "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id,"
                + " contract_status, seller_landlord_name, buyer_tenant_name, signed_date,"
                + " balance_due_date, sale_price) VALUES (?, ?, 'SALE', ?, 'ENDED', 'a', 'b',"
                + " '2020-01-01', '2020-02-01', 1) RETURNING id",
            Long.class,
            officeId,
            property,
            m);
    for (long contractId : new long[] {open, ended}) {
      jdbcTemplate.update(
          "INSERT INTO contract_party (contract_id, side, sequence_no, name, customer_id) VALUES"
              + " (?, 'BUYER_TENANT', 1, '당사자', ?)",
          contractId,
          customer);
    }
    long coordination =
        jdbcTemplate.queryForObject(
            "INSERT INTO visit_coordination (office_id, customer_id, manager_membership_id, status,"
                + " period_start_date, period_end_date) VALUES (?, ?, ?, 'COORDINATABLE',"
                + " '2026-12-01', '2026-12-05') RETURNING id",
            Long.class,
            officeId,
            customer,
            m);
    jdbcTemplate.update(
        "INSERT INTO visit_candidate_property (coordination_id, office_id, property_id,"
            + " property_office_id, sort_order) VALUES (?, ?, ?, ?, 1)",
        coordination,
        officeId,
        property,
        officeId);
    mockMvc
        .perform(authedGet("/api/customers/" + customer, cookie))
        .andExpect(jsonPath("$.upcomingContracts.length()").value(1))
        .andExpect(jsonPath("$.upcomingContracts[0].contractId").value(open))
        .andExpect(jsonPath("$.upcomingContracts[0].propertyName").value("계약 매물"))
        .andExpect(jsonPath("$.pastContracts.length()").value(1))
        .andExpect(jsonPath("$.pastContracts[0].contractStatus").value("ENDED"))
        .andExpect(jsonPath("$.visits[0].candidatePropertyCount").value(1))
        .andExpect(jsonPath("$.visits[0].status").value("COORDINATABLE"));
  }

  @Test
  void 현장안내_조회는_이름과_연락처만_주고_중개보조원과_다른_사무소는_거절한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    var other = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long id = createCustomer(cookie, "안내", "010-1111-2222", owner.membership().getId());
    mockMvc
        .perform(authedGet("/api/customers/" + id + "/assistant-view", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("안내"))
        .andExpect(jsonPath("$.phone").value("010-1111-2222"))
        .andExpect(jsonPath("$.memo").doesNotExist());
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    assertError(
        mockMvc.perform(authedGet("/api/customers/" + id + "/assistant-view", deputy)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet("/api/customers/" + id + "/assistant-view", cookieOf(other))),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
