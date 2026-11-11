package com.sairo.be.domain.contract.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class ContractChangeIntegrationTest extends ContractIntegrationTestBase {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static String effective(Instant at) {
    return at.atZone(KST).toOffsetDateTime().toString();
  }

  private static String statusBody(String to, Instant at, String extra) {
    return "{\"toStatus\":\""
        + to
        + "\",\"reason\":\"사실 확인\",\"effectiveAt\":\""
        + effective(at)
        + "\""
        + extra
        + "}";
  }

  /** 상세 응답에서 정정 본문을 만든다. 당사자는 partyId·sequenceNo를 포함한다. */
  private ObjectNode correctionBody(long contractId) throws Exception {
    JsonNode detail = getJson("/api/contracts/" + contractId, ownerCookie);
    ObjectNode body = MAPPER.createObjectNode();
    body.put("baseRevisionId", detail.get("currentRevisionId").longValue());
    body.put("reason", "오기록 정정");
    body.put("effectiveAt", effective(Instant.now().minus(1, ChronoUnit.HOURS)));
    for (String key :
        new String[] {
          "signedDate",
          "dealType",
          "salePrice",
          "depositAmount",
          "monthlyRentAmount",
          "balanceDueDate",
          "balanceDueDatePendingReason",
          "leaseStartDate",
          "leaseEndDate",
          "sellerLandlordParties",
          "buyerTenantParties"
        }) {
      body.set(key, detail.get(key));
    }
    return body;
  }

  @Test
  void 정정은_새_버전을_더하고_당사자를_유지_제외_추가하며_기준_버전과_당사자를_검사한다() throws Exception {
    long id = createJeonse(property, today().minusDays(5), today().plusYears(2));
    String url = "/api/contracts/" + id + "/corrections";
    long version = contractVersion(id);
    ObjectNode body = correctionBody(id);
    ArrayNode sellers = (ArrayNode) body.get("sellerLandlordParties");
    long keptSeller = sellers.get(0).get("partyId").longValue();
    long buyer = body.get("buyerTenantParties").get(0).get("partyId").longValue();

    ObjectNode stale = body.deepCopy();
    stale.put("baseRevisionId", 999_999L);
    assertError(
        mockMvc.perform(jsonPostAs(url, stale.toString(), ownerCookie).header("If-Match", version)),
        ErrorCode.CONTRACT_REVISION_CONFLICT);
    ObjectNode wrongSide = body.deepCopy();
    ((ObjectNode) ((ArrayNode) wrongSide.get("sellerLandlordParties")).get(0))
        .put("partyId", buyer);
    assertError(
        mockMvc.perform(
            jsonPostAs(url, wrongSide.toString(), ownerCookie).header("If-Match", version)),
        ErrorCode.INVALID_CONTRACT_PARTY);
    ObjectNode partial = body.deepCopy();
    partial.remove("leaseEndDate");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(url, partial.toString(), ownerCookie).header("If-Match", version)),
        "leaseEndDate",
        "REQUIRED");
    assertError(
        mockMvc.perform(jsonPostAs(url, body.toString(), ownerCookie).header("If-Match", 99)),
        ErrorCode.VERSION_CONFLICT);

    ObjectNode added = MAPPER.createObjectNode();
    added.putNull("partyId");
    added.put("sequenceNo", 1);
    added.put("name", "새임대");
    ObjectNode second = added.deepCopy();
    second.put("sequenceNo", 2);
    second.put("name", "둘째임대");
    ((ObjectNode) sellers.get(0)).put("sequenceNo", 3);
    ((ObjectNode) sellers.get(0)).put("name", "김임대정정");
    sellers.add(added);
    sellers.add(second);
    ArrayNode buyers = (ArrayNode) body.get("buyerTenantParties");
    buyers.removeAll();
    ObjectNode newBuyer = MAPPER.createObjectNode();
    newBuyer.putNull("partyId");
    newBuyer.put("sequenceNo", 1);
    newBuyer.put("name", "오임차");
    buyers.add(newBuyer);
    body.put("depositAmount", 310_000_000L);

    JsonNode revision =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(url, body.toString(), ownerCookie).header("If-Match", version))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionType").value("CORRECTED"))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.reason").value("오기록 정정"))
                .andExpect(jsonPath("$.officeId").value(officeId))
                .andExpect(jsonPath("$.version").value(version + 1))
                .andReturn());
    assertThat(revision.get("previousSnapshot").get("depositAmount").longValue())
        .isEqualTo(300_000_000L);
    JsonNode current = revision.get("currentSnapshot");
    assertThat(current.get("depositAmount").longValue()).isEqualTo(310_000_000L);
    assertThat(current.get("sellerLandlordParties").get(0).get("name").asString()).isEqualTo("새임대");
    assertThat(current.get("sellerLandlordParties").get(1).get("name").asString())
        .isEqualTo("둘째임대");
    assertThat(current.get("sellerLandlordParties").get(2).get("partyId").longValue())
        .isEqualTo(keptSeller);
    assertThat(current.get("sellerLandlordParties").get(2).get("sequenceNo").intValue())
        .isEqualTo(3);
    assertThat(
            longValue(
                "SELECT count(*) FROM contract_party WHERE id = ? AND removed_at IS NOT NULL",
                buyer))
        .isEqualTo(1);
    assertThat(text("SELECT seller_landlord_name FROM contract WHERE id = ?", id)).isEqualTo("새임대");
    assertThat(text("SELECT buyer_tenant_name FROM contract WHERE id = ?", id)).isEqualTo("오임차");
    JsonNode revisions = getJson("/api/contracts/" + id + "/revisions", ownerCookie);
    assertThat(revisions.get("totalElements").longValue()).isEqualTo(2);
    assertThat(revisions.get("items").get(1).get("supersedesRevisionId").longValue())
        .isEqualTo(revisions.get("items").get(0).get("revisionId").longValue());

    ObjectNode again = correctionBody(id);
    ArrayNode againSellers = (ArrayNode) again.get("sellerLandlordParties");
    againSellers.add(againSellers.get(0).deepCopy());
    assertError(
        mockMvc.perform(
            jsonPostAs(url, again.toString(), ownerCookie).header("If-Match", contractVersion(id))),
        ErrorCode.INVALID_CONTRACT_PARTY);
  }

  @Test
  void 체결일_정정은_SIGNED_사건을_대체하고_저장된_지난_달_결산을_다시_계산한다() throws Exception {
    YearMonth thisMonth = YearMonth.now(KST);
    YearMonth lastMonth = thisMonth.minusMonths(1);
    jdbcTemplate.update(
        "UPDATE office SET created_at = ? WHERE id = ?",
        Timestamp.from(thisMonth.minusMonths(3).atDay(1).atStartOfDay(KST).toInstant()),
        officeId);
    LocalDate signed = lastMonth.atDay(10);
    long id =
        create(property, sale(ownerMembership, signed, ""), ownerCookie)
            .get("contractId")
            .longValue();
    getJson("/api/settlements/" + lastMonth, ownerCookie);
    assertThat(
            longValue(
                "SELECT contract_signed_count FROM monthly_settlement WHERE office_id = ? AND"
                    + " year_month = ?",
                officeId,
                java.sql.Date.valueOf(lastMonth.atDay(1))))
        .isEqualTo(1);
    long signedEvent =
        longValue(
            "SELECT id FROM contract_status_history WHERE contract_id = ? AND to_status ="
                + " 'SIGNED'",
            id);

    ObjectNode body = correctionBody(id);
    body.put("signedDate", lastMonth.minusMonths(1).atDay(5).toString());
    mockMvc
        .perform(
            jsonPostAs("/api/contracts/" + id + "/corrections", body.toString(), ownerCookie)
                .header("If-Match", contractVersion(id)))
        .andExpect(status().isCreated());

    assertThat(
            longValue(
                "SELECT contract_signed_count FROM monthly_settlement WHERE office_id = ? AND"
                    + " year_month = ?",
                officeId,
                java.sql.Date.valueOf(lastMonth.atDay(1))))
        .isZero();
    assertThat(
            longValue(
                "SELECT version FROM monthly_settlement WHERE office_id = ? AND year_month = ?",
                officeId,
                java.sql.Date.valueOf(lastMonth.atDay(1))))
        .isEqualTo(2);
    JsonNode items = getJson("/api/contracts/" + id + "/status-history", ownerCookie).get("items");
    assertThat(items.size()).isEqualTo(2);
    JsonNode replacement = items.get(0);
    assertThat(replacement.get("supersedesHistoryId").longValue()).isEqualTo(signedEvent);
    assertThat(replacement.get("effectiveAt").asString())
        .isEqualTo(lastMonth.minusMonths(1).atDay(5) + "T00:00:00+09:00");
    getJson("/api/settlements/" + lastMonth.minusMonths(1), ownerCookie);
    mockMvc
        .perform(authedGet("/api/settlements/" + lastMonth.minusMonths(1), ownerCookie))
        .andExpect(jsonPath("$.contractSignedCount").value(1));
  }

  @Test
  void 계약_상태는_체결_뒤에만_바꾸고_매매_해제_무효_이전상태_대체_이력을_검사한다() throws Exception {
    long saleId =
        create(property, sale(ownerMembership, today().minusDays(3), ""), ownerCookie)
            .get("contractId")
            .longValue();
    String url = "/api/contracts/" + saleId + "/status";
    Instant now = Instant.now();
    assertError(
        mockMvc.perform(jsonPatchAs(url, statusBody("TERMINATED", now, ""), ownerCookie, 1)),
        ErrorCode.SALE_CANNOT_TERMINATE);
    assertFieldError(
        mockMvc.perform(
            jsonPatchAs(url, statusBody("ENDED", now.plusSeconds(3600), ""), ownerCookie, 1)),
        "effectiveAt",
        "OUT_OF_RANGE");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, statusBody("VOID", now, ""), ownerCookie, 1)),
        "previousStatus",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, statusBody("SIGNED", now, ""), ownerCookie, 1)),
        "toStatus",
        "NOT_ALLOWED");
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                url,
                statusBody("CANCELLED", today().minusDays(10).atStartOfDay(KST).toInstant(), ""),
                ownerCookie,
                1)),
        ErrorCode.INVALID_TRANSITION);

    mockMvc
        .perform(jsonPatchAs(url, statusBody("ENDED", now.minusSeconds(60), ""), ownerCookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractStatus").value("ENDED"))
        .andExpect(jsonPath("$.version").value(2));
    assertError(
        mockMvc.perform(jsonPatchAs(url, statusBody("CANCELLED", now, ""), ownerCookie, 2)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                url, statusBody("VOID", now, ",\"previousStatus\":\"SIGNED\""), ownerCookie, 2)),
        ErrorCode.INVALID_TRANSITION);
    long ended =
        longValue(
            "SELECT id FROM contract_status_history WHERE contract_id = ? AND to_status = 'ENDED'",
            saleId);
    mockMvc
        .perform(
            jsonPatchAs(
                url,
                statusBody("CANCELLED", now.minusSeconds(120), ",\"supersedesHistoryId\":" + ended),
                ownerCookie,
                2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractStatus").value("CANCELLED"));
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                url,
                statusBody("ENDED", now.minusSeconds(30), ",\"supersedesHistoryId\":" + ended),
                ownerCookie,
                3)),
        ErrorCode.INVALID_TRANSITION);
    mockMvc
        .perform(
            jsonPatchAs(
                url, statusBody("VOID", now, ",\"previousStatus\":\"CANCELLED\""), ownerCookie, 3))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractStatus").value("VOID"));
    JsonNode history = getJson("/api/contracts/" + saleId + "/status-history", ownerCookie);
    assertThat(history.get("totalElements").longValue()).isEqualTo(4);
    assertThat(history.get("items").get(3).get("fromStatus").asString()).isEqualTo("CANCELLED");
    assertThat(history.get("items").get(3).get("historyId").longValue()).isPositive();

    long draft =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/properties/" + property + "/contracts/drafts",
                            "{\"dealType\":\"SALE\",\"managingMembershipId\":"
                                + ownerMembership
                                + "}",
                            ownerCookie))
                    .andReturn())
            .get("contractId")
            .longValue();
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/contracts/" + draft + "/status",
                statusBody("ENDED", now, ""),
                ownerCookie,
                1)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
  }

  @Test
  void 잔금_상태는_독립적으로_기록하고_UNDECIDED와_같은_값은_받지_않는다() throws Exception {
    long id = createJeonse(property, today(), today().plusYears(2));
    String url = "/api/contracts/" + id + "/settlement-status";
    assertFieldError(
        mockMvc.perform(jsonPatchAs(url, "{\"toStatus\":\"UNDECIDED\"}", ownerCookie, 1)),
        "toStatus",
        "NOT_ALLOWED");
    mockMvc
        .perform(
            jsonPatchAs(
                url,
                "{\"toStatus\":\"COMPLETE\",\"reason\":\"잔금 완납\",\"effectiveAt\":\""
                    + effective(Instant.now().minusSeconds(60))
                    + "\"}",
                ownerCookie,
                1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settlementStatus").value("COMPLETE"))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(text("SELECT contract_status FROM contract WHERE id = ?", id)).isEqualTo("SIGNED");
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"toStatus\":\"COMPLETE\"}", ownerCookie, 2)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        mockMvc.perform(jsonPatchAs(url, "{\"toStatus\":\"NONE\"}", ownerCookie, 1)),
        ErrorCode.VERSION_CONFLICT);
    assertThat(
            longValue(
                "SELECT count(*) FROM contract_status_history WHERE contract_id = ? AND track ="
                    + " 'SETTLEMENT' AND from_status = 'UNDECIDED'",
                id))
        .isEqualTo(1);
  }

  @Test
  void 중복_제외는_대표만_하고_목록과_체결_집계에서_빼며_다시_제외하지_못한다() throws Exception {
    long first = createJeonse(property, today(), today().plusYears(2));
    long second =
        create(
                addressedProperty(ownerCookie, "두 번째", "서울시 2"),
                jeonse(ownerMembership, today(), today(), today().plusYears(2), ""),
                ownerCookie)
            .get("contractId")
            .longValue();
    String url = "/api/contracts/" + second + "/duplicate-exclusion";
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                    url,
                    "{\"duplicateOfContractId\":" + second + ",\"reason\":\"중복\"}",
                    ownerCookie)
                .header("If-Match", 1)),
        "duplicateOfContractId",
        "INVALID_VALUE");
    mockMvc
        .perform(
            jsonPostAs(
                    url,
                    "{\"duplicateOfContractId\":" + first + ",\"reason\":\"두 번 입력\"}",
                    ownerCookie)
                .header("If-Match", 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.duplicateOfContractId").value(first))
        .andExpect(jsonPath("$.version").value(2));
    assertError(
        mockMvc.perform(
            jsonPostAs(
                    url,
                    "{\"duplicateOfContractId\":" + first + ",\"reason\":\"두 번 입력\"}",
                    ownerCookie)
                .header("If-Match", 2)),
        ErrorCode.CONTRACT_ACTION_NOT_ALLOWED);
    assertThat(getJson("/api/contracts", ownerCookie).get("totalElements").longValue())
        .isEqualTo(1);
    JsonNode included = getJson("/api/contracts?includeExcluded=true", ownerCookie);
    assertThat(included.get("totalElements").longValue()).isEqualTo(2);
    assertThat(included.get("items").get(0).get("excluded").booleanValue()).isTrue();
    mockMvc
        .perform(authedGet("/api/contracts/" + second, ownerCookie))
        .andExpect(jsonPath("$.duplicateExcluded").value(true))
        .andExpect(jsonPath("$.duplicateOfContractId").value(first))
        .andExpect(jsonPath("$.duplicateExclusionReason").value("두 번 입력"));
    mockMvc
        .perform(authedGet("/api/settlements/" + YearMonth.now(KST), ownerCookie))
        .andExpect(jsonPath("$.contractSignedCount").value(1));
    assertThat(longValue("SELECT count(*) FROM contract_revision WHERE contract_id = ?", second))
        .isEqualTo(1);
  }
}
