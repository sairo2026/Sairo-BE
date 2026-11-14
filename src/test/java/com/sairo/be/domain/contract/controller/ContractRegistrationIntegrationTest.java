package com.sairo.be.domain.contract.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.contract.service.LeasePeriodService;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ContractRegistrationIntegrationTest extends ContractIntegrationTestBase {

  @Autowired private LeasePeriodService leasePeriodService;

  private Map<String, Function<Cookie, MockHttpServletRequestBuilder>> endpoints(
      long propertyId, long contractId, long manager) {
    String base = "/api/contracts/" + contractId;
    String status =
        "{\"toStatus\":\"ENDED\",\"reason\":\"만기\",\"effectiveAt\":\"2026-01-01T00:00:00+09:00\"}";
    Map<String, Function<Cookie, MockHttpServletRequestBuilder>> map = new LinkedHashMap<>();
    map.put("GET /api/contracts", c -> authedGet("/api/contracts", c));
    map.put(
        "POST drafts",
        c ->
            jsonPostAs(
                "/api/properties/" + propertyId + "/contracts/drafts",
                "{\"dealType\":\"SALE\",\"managingMembershipId\":" + manager + "}",
                c));
    map.put("PATCH draft", c -> jsonPatchAs(base + "/draft", "{\"memo\":\"수정\"}", c, 1));
    map.put(
        "POST sign",
        c -> AuthFlowSupport.withCsrf(withCookie(post(base + "/sign").header("If-Match", "1"), c)));
    map.put("DELETE draft", c -> deleteAs(base + "/draft", c));
    map.put(
        "POST create",
        c ->
            jsonPostAs(
                "/api/properties/" + propertyId + "/contracts", sale(manager, today(), ""), c));
    map.put("GET detail", c -> authedGet(base, c));
    map.put(
        "POST corrections",
        c -> jsonPostAs(base + "/corrections", "{}", c).header("If-Match", "1"));
    map.put("GET revisions", c -> authedGet(base + "/revisions", c));
    map.put("PATCH status", c -> jsonPatchAs(base + "/status", status, c, 1));
    map.put(
        "PATCH settlement-status",
        c -> jsonPatchAs(base + "/settlement-status", "{\"toStatus\":\"SCHEDULED\"}", c, 1));
    map.put(
        "POST renew",
        c ->
            jsonPostAs(
                base + "/renew", jeonse(manager, today(), today(), today().plusYears(2), ""), c));
    map.put("GET status-history", c -> authedGet(base + "/status-history", c));
    map.put(
        "POST duplicate-exclusion",
        c -> {
          var builder =
              jsonPostAs(
                  base + "/duplicate-exclusion",
                  "{\"duplicateOfContractId\":1,\"reason\":\"중복\"}",
                  c);
          return builder.header("If-Match", "1");
        });
    return map;
  }

  private static MockHttpServletRequestBuilder withCookie(
      MockHttpServletRequestBuilder builder, Cookie cookie) {
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return builder;
  }

  @Test
  void 열네_개_엔드포인트는_로그인_없으면_401_소속_없거나_중개보조원이면_403이다() throws Exception {
    long contractId = createJeonse(property, today(), today().plusYears(2));
    Cookie plain = kakaoSessionFor(saveEmailUser("contract-plain@example.com", "010-4545-1212"));
    Cookie deputy = cookieOf(staffIn(owner.office(), OfficeMembershipRole.DEPUTY));
    var map = endpoints(property, contractId, ownerMembership);
    assertThat(map).hasSize(14);
    for (var entry : map.entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(entry.getValue().apply(plain)), ErrorCode.FORBIDDEN);
      assertError(mockMvc.perform(entry.getValue().apply(deputy)), ErrorCode.FORBIDDEN);
    }
    Cookie agent = cookieOf(staffIn(owner.office(), OfficeMembershipRole.AGENT));
    assertError(
        mockMvc.perform(map.get("POST duplicate-exclusion").apply(agent)), ErrorCode.FORBIDDEN);
    assertThat(longValue("SELECT count(*) FROM contract")).isEqualTo(1);
  }

  @Test
  void 다른_사무소의_매물과_계약은_404다() throws Exception {
    long contractId = createJeonse(property, today(), today().plusYears(2));
    TestData.Staff other = newStaff(OfficeMembershipRole.OWNER);
    Cookie otherCookie = cookieOf(other);
    var map = endpoints(property, contractId, other.membership().getId());
    map.remove("GET /api/contracts");
    for (var entry : map.entrySet()) {
      assertError(
          mockMvc.perform(entry.getValue().apply(otherCookie)), ErrorCode.RESOURCE_NOT_FOUND);
    }
    mockMvc
        .perform(authedGet("/api/contracts", otherCookie))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 초안은_부분_입력으로_저장하고_수정한_뒤_체결하면_이력과_버전과_거래제안이_남는다() throws Exception {
    long sameOffer = insertOffer(property, "JEONSE", "AVAILABLE", null, 300_000_000L, null);
    long otherOffer = insertOffer(property, "SALE", "NEGOTIATING", 500_000_000L, null, null);
    JsonNode draft =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/properties/" + property + "/contracts/drafts",
                        "{\"dealType\":\"JEONSE\",\"managingMembershipId\":"
                            + ownerMembership
                            + ",\"sellerLandlordParties\":[]}",
                        ownerCookie))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contractStatus").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.officeId").value(officeId))
                .andReturn());
    long id = draft.get("contractId").longValue();
    String url = "/api/contracts/" + id;
    getJson(url, ownerCookie);
    mockMvc
        .perform(authedGet(url, ownerCookie))
        .andExpect(jsonPath("$.sellerLandlordParties.length()").value(0))
        .andExpect(jsonPath("$.currentRevisionId").doesNotExist())
        .andExpect(jsonPath("$.propertyAddress").value("서울시 마포구 합정동 1"));

    assertFieldError(
        mockMvc.perform(jsonPatchAs(url + "/draft", "{\"memo\":\"x\"}", ownerCookie, null)),
        "If-Match",
        "REQUIRED");
    assertError(
        mockMvc.perform(jsonPatchAs(url + "/draft", "{\"memo\":\"x\"}", ownerCookie, 5)),
        ErrorCode.VERSION_CONFLICT);
    assertError(mockMvc.perform(sign(url, 1)), ErrorCode.INCOMPLETE_FOR_SIGNING)
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'sellerLandlordParties')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'depositAmount')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'balanceDueDatePendingReason')]").exists());

    LocalDate start = today().minusDays(10);
    String patch =
        "{\"signedDate\":\""
            + today().minusDays(20)
            + "\",\"depositAmount\":300000000,\"leaseStartDate\":\""
            + start
            + "\",\"leaseEndDate\":\""
            + start.plusYears(2)
            + "\",\"balanceDueDatePendingReason\":\"대출 승인 대기\",\"sellerLandlordParties\":"
            + "[{\"name\":\"김임대\",\"phone\":\"010-1111-2222\"},{\"name\":\"한임대\"}],"
            + "\"buyerTenantParties\":[{\"name\":\"이임차\"}]}";
    mockMvc
        .perform(jsonPatchAs(url + "/draft", patch, ownerCookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    assertThat(text("SELECT seller_landlord_name FROM contract WHERE id = ?", id)).isEqualTo("김임대");
    mockMvc
        .perform(
            jsonPatchAs(
                url + "/draft", "{\"sellerLandlordParties\":[{\"name\":\"한임대\"}]}", ownerCookie, 2))
        .andExpect(status().isOk());
    assertThat(text("SELECT seller_landlord_name FROM contract WHERE id = ?", id)).isEqualTo("한임대");
    assertThat(longValue("SELECT count(*) FROM contract_party WHERE contract_id = ?", id))
        .isEqualTo(2);

    mockMvc
        .perform(sign(url, 3))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractStatus").value("SIGNED"))
        .andExpect(jsonPath("$.version").value(4));
    assertThat(text("SELECT lease_period_status FROM contract WHERE id = ?", id))
        .isEqualTo("IN_PROGRESS");
    assertThat(text("SELECT status FROM property_offer WHERE id = ?", sameOffer))
        .isEqualTo("COMPLETED");
    assertThat(text("SELECT status FROM property_offer WHERE id = ?", otherOffer))
        .isEqualTo("NEGOTIATING");
    JsonNode history = getJson(url + "/status-history", ownerCookie);
    assertThat(history.get("totalElements").longValue()).isEqualTo(2);
    JsonNode signed = history.get("items").get(0);
    assertThat(signed.get("track").asString()).isEqualTo("CONTRACT");
    assertThat(signed.get("fromStatus").asString()).isEqualTo("DRAFT");
    assertThat(signed.get("effectiveAt").asString())
        .isEqualTo(today().minusDays(20) + "T00:00:00+09:00");
    assertThat(signed.get("changedByMemberName").isNull()).isFalse();
    JsonNode revisions = getJson(url + "/revisions", ownerCookie);
    assertThat(revisions.get("items").get(0).get("revisionType").asString()).isEqualTo("SIGNED");
    assertThat(
            revisions
                .get("items")
                .get(0)
                .get("currentSnapshot")
                .get("sellerLandlordParties")
                .size())
        .isEqualTo(1);
    assertThat(revisions.get("items").get(0).get("previousSnapshot").isNull()).isTrue();

    assertError(
        mockMvc.perform(deleteAs(url + "/draft", ownerCookie)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    assertError(
        mockMvc.perform(jsonPatchAs(url + "/draft", "{\"memo\":\"x\"}", ownerCookie, 4)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    assertError(mockMvc.perform(sign(url, 4)), ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
  }

  private MockHttpServletRequestBuilder sign(String url, long version) {
    return AuthFlowSupport.withCsrf(
        post(url + "/sign").header("If-Match", String.valueOf(version)).cookie(ownerCookie));
  }

  @Test
  void 초안_삭제는_당사자와_함께_지우고_보관_매물에는_초안을_만들지_못한다() throws Exception {
    long id =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/properties/" + property + "/contracts/drafts",
                            "{\"dealType\":\"SALE\",\"managingMembershipId\":"
                                + ownerMembership
                                + ",\"buyerTenantParties\":[{\"name\":\"정매수\"}]}",
                            ownerCookie))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("contractId")
            .longValue();
    mockMvc
        .perform(deleteAs("/api/contracts/" + id + "/draft", ownerCookie))
        .andExpect(status().isNoContent());
    assertThat(longValue("SELECT count(*) FROM contract_party")).isZero();
    archive(property);
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/properties/" + property + "/contracts/drafts",
                "{\"dealType\":\"SALE\",\"managingMembershipId\":" + ownerMembership + "}",
                ownerCookie)),
        ErrorCode.PROPERTY_ARCHIVED);
  }

  @Test
  void 즉시_체결은_필수값과_형식과_매물_겹침을_검사한다() throws Exception {
    String url = "/api/properties/" + property + "/contracts";
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                url,
                jeonse(ownerMembership, today(), today(), today().minusDays(1), ""),
                ownerCookie)),
        "leaseEndDate",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, "{\"managingMembershipId\":1}", ownerCookie)),
        "dealType",
        "REQUIRED");
    assertError(
            mockMvc.perform(
                jsonPostAs(
                    url,
                    "{\"dealType\":\"MONTHLY\",\"managingMembershipId\":" + ownerMembership + "}",
                    ownerCookie)),
            ErrorCode.INCOMPLETE_FOR_SIGNING)
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'monthlyRentAmount')]").exists())
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'signedDate')]").exists());
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, sale(ownerMembership, today().plusDays(1), ""), ownerCookie)),
        "signedDate",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, sale(ownerMembership, today(), ",\"depositAmount\":1"), ownerCookie)),
        "depositAmount",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url,
                sale(ownerMembership, today(), ",\"memo\":\"주민번호 900101-1234567\""),
                ownerCookie)),
        ErrorCode.SENSITIVE_DATA_DETECTED);
    long deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY).membership().getId();
    assertFieldError(
        mockMvc.perform(jsonPostAs(url, sale(deputy, today(), ""), ownerCookie)),
        "managingMembershipId",
        "INVALID_VALUE");

    long primary = insertCustomer(officeId, ownerMembership, "남는 고객");
    long merged = insertCustomer(officeId, ownerMembership, "합쳐진 고객");
    mergeRow(primary, merged, ownerMembership);
    String body =
        "{\"dealType\":\"SALE\",\"managingMembershipId\":"
            + ownerMembership
            + ",\"signedDate\":\""
            + today()
            + "\",\"salePrice\":1000,\"balanceDueDate\":\""
            + today()
            + "\",\"sellerLandlordParties\":[{\"name\":\"박매도\",\"customerId\":"
            + merged
            + "}],\"buyerTenantParties\":[{\"name\":\"정매수\"}]}";
    JsonNode created = create(property, body, ownerCookie);
    assertThat(created.get("contractStatus").asString()).isEqualTo("SIGNED");
    assertThat(created.get("sellerLandlordParties").get(0).get("customerId").longValue())
        .isEqualTo(primary);
    assertThat(created.get("sellerLandlordParties").get(0).get("partyId").longValue()).isPositive();
    assertError(
        mockMvc.perform(jsonPostAs(url, body, ownerCookie)),
        ErrorCode.PROPERTY_HAS_ACTIVE_CONTRACT);

    long leased = createJeonse(property, today().minusDays(30), today().plusYears(2));
    assertThat(leased).isPositive();
    assertError(
        mockMvc.perform(
            jsonPostAs(
                url,
                jeonse(ownerMembership, today(), today().plusYears(2), today().plusYears(4), ""),
                ownerCookie)),
        ErrorCode.PROPERTY_HAS_ACTIVE_CONTRACT);
    create(
        property,
        jeonse(
            ownerMembership, today(), today().plusYears(2).plusDays(1), today().plusYears(4), ""),
        ownerCookie);
  }

  @Test
  void 남의_사무소_고객을_당사자로_연결하면_400이다() throws Exception {
    TestData.Staff other = newStaff(OfficeMembershipRole.OWNER);
    long foreign = insertCustomer(other.office().getId(), other.membership().getId(), "남의 고객");
    String body =
        sale(ownerMembership, today(), "")
            .replace("{\"name\":\"정매수\"}", "{\"name\":\"정매수\",\"customerId\":" + foreign + "}");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/properties/" + property + "/contracts", body, ownerCookie)),
        "buyerTenantParties[0].customerId",
        "INVALID_VALUE");
    assertThat(longValue("SELECT count(*) FROM contract")).isZero();
  }

  @Test
  void 목록은_상태_주소_당사자_만기_중복제외로_거르고_서류_누락과_당사자_수를_돌려준다() throws Exception {
    long other = addressedProperty(ownerCookie, "다른 매물", "부산시 해운대구 우동 2");
    long saleId =
        create(other, sale(ownerMembership, today(), ""), ownerCookie)
            .get("contractId")
            .longValue();
    long leaseId = createJeonse(property, today().minusYears(2), today().plusDays(30));
    jdbcTemplate.update(
        "INSERT INTO contract (office_id, property_id, deal_type, managing_membership_id)"
            + " VALUES (?, ?, 'SALE', ?)",
        officeId,
        property,
        ownerMembership);

    JsonNode all = getJson("/api/contracts", ownerCookie);
    assertThat(all.get("totalElements").longValue()).isEqualTo(3);
    assertThat(all.get("size").intValue()).isEqualTo(25);
    JsonNode draft = all.get("items").get(0);
    assertThat(draft.get("contractStatus").asString()).isEqualTo("DRAFT");
    assertThat(draft.get("missingDocumentTypes").size()).isZero();
    assertThat(draft.get("sellerLandlordPartyCount").intValue()).isZero();

    JsonNode saleItem = getJson("/api/contracts?partyName=최매", ownerCookie).get("items").get(0);
    assertThat(saleItem.get("contractId").longValue()).isEqualTo(saleId);
    assertThat(saleItem.get("sellerLandlordName").asString()).isEqualTo("박매도");
    assertThat(saleItem.get("sellerLandlordPartyCount").intValue()).isEqualTo(2);
    assertThat(saleItem.get("missingDocumentTypes").size()).isEqualTo(2);
    assertThat(saleItem.get("leaseEndDate").isNull()).isTrue();
    assertThat(saleItem.get("managingMemberName").asString()).isNotBlank();

    assertThat(
            getJson("/api/contracts?propertyAddress=해운대", ownerCookie)
                .get("totalElements")
                .longValue())
        .isEqualTo(1);
    assertThat(
            getJson("/api/contracts?contractStatus=SIGNED", ownerCookie)
                .get("totalElements")
                .longValue())
        .isEqualTo(2);
    JsonNode maturity = getJson("/api/contracts?leaseEndWithinDays=90", ownerCookie);
    assertThat(maturity.get("totalElements").longValue()).isEqualTo(1);
    assertThat(maturity.get("items").get(0).get("contractId").longValue()).isEqualTo(leaseId);

    assertFieldError(
        mockMvc.perform(authedGet("/api/contracts?contractStatus=SIGNED,ENDED", ownerCookie)),
        "contractStatus",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/contracts?leaseEndWithinDays=0", ownerCookie)),
        "leaseEndWithinDays",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(authedGet("/api/contracts?size=20", ownerCookie)), "size", "NOT_ALLOWED");
  }

  @Test
  void 재계약은_전세_월세만_되고_직전_계약을_바꾸지_않는다() throws Exception {
    long saleId =
        create(
                addressedProperty(ownerCookie, "매매 매물", "서울시 종로구 1"),
                sale(ownerMembership, today(), ""),
                ownerCookie)
            .get("contractId")
            .longValue();
    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/contracts/" + saleId + "/renew",
                jeonse(ownerMembership, today(), today(), today().plusYears(2), ""),
                ownerCookie)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);

    LocalDate end = today().plusDays(60);
    long previous = createJeonse(property, today().minusYears(2), end);
    long version = contractVersion(previous);
    String renewUrl = "/api/contracts/" + previous + "/renew";
    assertError(
        mockMvc.perform(
            jsonPostAs(
                renewUrl,
                jeonse(ownerMembership, today(), end, end.plusYears(2), ""),
                ownerCookie)),
        ErrorCode.PROPERTY_HAS_ACTIVE_CONTRACT);
    assertFieldError(
        mockMvc.perform(jsonPostAs(renewUrl, sale(ownerMembership, today(), ""), ownerCookie)),
        "dealType",
        "NOT_ALLOWED");
    JsonNode renewed =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        renewUrl,
                        jeonse(ownerMembership, today(), end.plusDays(1), end.plusYears(2), ""),
                        ownerCookie))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.previousContractId").value(previous))
                .andExpect(jsonPath("$.contractStatus").value("SIGNED"))
                .andReturn());
    assertThat(
            text(
                "SELECT lease_period_status FROM contract WHERE id = ?",
                renewed.get("contractId").longValue()))
        .isEqualTo("BEFORE_START");
    assertThat(contractVersion(previous)).isEqualTo(version);
    assertThat(text("SELECT contract_status FROM contract WHERE id = ?", previous))
        .isEqualTo("SIGNED");
  }

  @Test
  void 임대기간_배치는_날짜가_지난_체결_계약의_표시값만_바꾸고_처리자를_비운다() throws Exception {
    long id = createJeonse(property, today().plusDays(10), today().plusYears(2));
    assertThat(text("SELECT lease_period_status FROM contract WHERE id = ?", id))
        .isEqualTo("BEFORE_START");
    jdbcTemplate.update(
        "UPDATE contract SET lease_start_date = ?, lease_end_date = ? WHERE id = ?",
        java.sql.Date.valueOf(today().minusYears(2)),
        java.sql.Date.valueOf(today().minusDays(1)),
        id);
    long version = contractVersion(id);
    assertThat(leasePeriodService.refreshAll()).isEqualTo(1);
    assertThat(leasePeriodService.refreshAll()).isZero();
    assertThat(text("SELECT lease_period_status FROM contract WHERE id = ?", id))
        .isEqualTo("MATURITY_CONFIRMATION_NEEDED");
    assertThat(contractVersion(id)).isEqualTo(version + 1);
    assertThat(text("SELECT contract_status FROM contract WHERE id = ?", id)).isEqualTo("SIGNED");
    JsonNode items = getJson("/api/contracts/" + id + "/status-history", ownerCookie).get("items");
    JsonNode batch = null;
    for (JsonNode item : items) {
      if (item.get("changedByMembershipId").isNull()) {
        batch = item;
      }
    }
    assertThat(batch).isNotNull();
    assertThat(batch.get("track").asString()).isEqualTo("LEASE_PERIOD");
    assertThat(batch.get("fromStatus").asString()).isEqualTo("BEFORE_START");
    assertThat(batch.get("changedByMemberName").isNull()).isTrue();
    assertThat(batch.get("effectiveAt").asString()).isEqualTo(today() + "T00:00:00+09:00");
  }

  @Test
  void 구성원_상세는_진행_중_계약을_세고_정지하면_계약_담당이_대표에게_넘어간다() throws Exception {
    TestData.Staff agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    long agentId = agent.membership().getId();
    long open =
        create(property, sale(agentId, today(), ""), cookieOf(agent)).get("contractId").longValue();
    long ended =
        createJeonse(
            addressedProperty(ownerCookie, "종료 매물", "서울시 1"), today(), today().plusYears(1));
    jdbcTemplate.update(
        "UPDATE contract SET managing_membership_id = ?, contract_status = 'ENDED' WHERE id = ?",
        agentId,
        ended);
    mockMvc
        .perform(authedGet("/api/memberships/" + agentId, ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedCounts.contracts").value(1));
    mockMvc
        .perform(
            jsonPostAs(
                "/api/memberships/" + agentId + "/suspend", "{\"reason\":\"점검\"}", ownerCookie))
        .andExpect(status().isOk());
    assertThat(longValue("SELECT managing_membership_id FROM contract WHERE id = ?", open))
        .isEqualTo(ownerMembership);
    assertThat(longValue("SELECT managing_membership_id FROM contract WHERE id = ?", ended))
        .isEqualTo(agentId);
  }
}
