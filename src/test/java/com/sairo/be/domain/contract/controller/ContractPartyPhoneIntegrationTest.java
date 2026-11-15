package com.sairo.be.domain.contract.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@IntegrationTest
class ContractPartyPhoneIntegrationTest extends ContractIntegrationTestBase {

  private String draftBody(String sellerPhone) {
    return "{\"dealType\":\"JEONSE\",\"managingMembershipId\":"
        + ownerMembership
        + ",\"sellerLandlordParties\":[{\"name\":\"김임대\",\"phone\":\""
        + sellerPhone
        + "\"}]}";
  }

  @Test
  void 초안_생성은_숫자_7자_이상_연락처를_받고_그_미만은_필드_오류로_거절한다() throws Exception {
    mockMvc
        .perform(
            jsonPostAs(
                "/api/properties/" + property + "/contracts/drafts",
                draftBody("+82 (2) 123-4567"),
                ownerCookie))
        .andExpect(status().isCreated());
    for (String phone : new String[] {"12345", "---", "없음"}) {
      assertFieldError(
          mockMvc.perform(
              jsonPostAs(
                  "/api/properties/" + property + "/contracts/drafts",
                  draftBody(phone),
                  ownerCookie)),
          "sellerLandlordParties[0].phone",
          "INVALID_FORMAT");
    }
    String signed =
        jeonse(ownerMembership, today(), today().minusDays(1), today().plusYears(2), "")
            .replace(
                "\"buyerTenantParties\":[{\"name\":\"이임차\"}]",
                "\"buyerTenantParties\":[{\"name\":\"이임차\",\"phone\":\"12\"}]");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/properties/" + property + "/contracts", signed, ownerCookie)),
        "buyerTenantParties[0].phone",
        "INVALID_FORMAT");
  }

  @Test
  void 초안_수정은_이미_저장된_위반_값을_그대로_보내면_막지_않고_새_위반_값은_거절한다() throws Exception {
    JsonNode draft =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        "/api/properties/" + property + "/contracts/drafts",
                        draftBody("010-1111-2222"),
                        ownerCookie))
                .andExpect(status().isCreated())
                .andReturn());
    long id = draft.get("contractId").longValue();
    jdbcTemplate.update("UPDATE contract_party SET phone = '02-12' WHERE contract_id = ?", id);
    String url = "/api/contracts/" + id + "/draft";

    mockMvc
        .perform(
            jsonPatchAs(
                url,
                "{\"sellerLandlordParties\":[{\"name\":\"김임대\",\"phone\":\"02-12\"}]}",
                ownerCookie,
                contractVersion(id)))
        .andExpect(status().isOk());
    assertFieldError(
        mockMvc.perform(
            jsonPatchAs(
                url,
                "{\"sellerLandlordParties\":[{\"name\":\"김임대\",\"phone\":\"02-13\"}]}",
                ownerCookie,
                contractVersion(id))),
        "sellerLandlordParties[0].phone",
        "INVALID_FORMAT");
  }

  @Test
  void 정정은_바꾸지_않은_기존_위반_값은_통과시키고_바꾼_위반_값은_거절한다() throws Exception {
    long id = createJeonse(property, today().minusDays(5), today().plusYears(2));
    jdbcTemplate.update(
        "UPDATE contract_party SET phone = '02-12' WHERE contract_id = ? AND phone IS NOT NULL",
        id);
    ObjectNode unchanged = correctionOf(id);
    ((ObjectNode) ((ArrayNode) unchanged.get("sellerLandlordParties")).get(0))
        .put("phone", "02-12");
    mockMvc
        .perform(
            jsonPostAs("/api/contracts/" + id + "/corrections", unchanged.toString(), ownerCookie)
                .header("If-Match", contractVersion(id)))
        .andExpect(status().isCreated());

    ObjectNode changed = correctionOf(id);
    ((ObjectNode) ((ArrayNode) changed.get("sellerLandlordParties")).get(0)).put("phone", "99");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs("/api/contracts/" + id + "/corrections", changed.toString(), ownerCookie)
                .header("If-Match", contractVersion(id))),
        "sellerLandlordParties[0].phone",
        "INVALID_FORMAT");
  }
}
