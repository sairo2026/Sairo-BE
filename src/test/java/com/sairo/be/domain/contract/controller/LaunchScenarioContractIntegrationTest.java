package com.sairo.be.domain.contract.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.contract.service.ContractFollowupService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** 개발플로우 4장 시나리오 ⑥을 실제 엔드포인트로 잇는 출시 검증 시험이다. */
@IntegrationTest
class LaunchScenarioContractIntegrationTest extends ContractIntegrationTestBase {

  @Autowired private ContractFollowupService followupService;

  private long d90Count(long contractId) {
    return longValue(
        "SELECT count(*) FROM contract_followup_task WHERE contract_id = ? AND task_type ="
            + " 'D90_FOLLOWUP'",
        contractId);
  }

  private MockHttpServletRequestBuilder sign(long contractId, long version) {
    return AuthFlowSupport.withCsrf(
        post("/api/contracts/" + contractId + "/sign")
            .header("If-Match", String.valueOf(version))
            .cookie(ownerCookie));
  }

  @Test
  void 시나리오6_전세_초안을_체결하고_재계약한_뒤_기존_계약을_따로_정상_종료하고_D90_후속업무를_확인한다() throws Exception {
    // 0. 주소가 있는 매물을 등록 API로 만든다
    long propertyId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/properties",
                            "{\"name\":\"전세 매물\",\"categoryId\":"
                                + categoryIdOf("APARTMENT")
                                + ",\"address\":\"서울시 마포구 망원동 7\"}",
                            ownerCookie))
                    .andExpect(status().isCreated())
                    .andReturn())
            .get("propertyId")
            .longValue();

    // 1. 전세 초안 부분 저장
    long id =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            "/api/properties/" + propertyId + "/contracts/drafts",
                            "{\"dealType\":\"JEONSE\",\"managingMembershipId\":"
                                + ownerMembership
                                + ",\"sellerLandlordParties\":[]}",
                            ownerCookie))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.contractStatus").value("DRAFT"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andReturn())
            .get("contractId")
            .longValue();
    String url = "/api/contracts/" + id;
    assertError(mockMvc.perform(sign(id, 1)), ErrorCode.INCOMPLETE_FOR_SIGNING)
        .andExpect(jsonPath("$.fieldErrors[?(@.field == 'depositAmount')]").exists());
    assertThat(text("SELECT contract_status FROM contract WHERE id = ?", id)).isEqualTo("DRAFT");

    // 2. 필수값을 채우고 체결(만기가 90일 안이라 D-90 후속업무가 바로 생긴다)
    LocalDate end = today().plusDays(60);
    LocalDate start = end.minusYears(2);
    String patch =
        "{\"signedDate\":\""
            + start.minusDays(10)
            + "\",\"depositAmount\":300000000,\"leaseStartDate\":\""
            + start
            + "\",\"leaseEndDate\":\""
            + end
            + "\",\"balanceDueDate\":\""
            + start
            + "\",\"sellerLandlordParties\":[{\"name\":\"김임대\",\"phone\":\"010-1111-2222\"}],"
            + "\"buyerTenantParties\":[{\"name\":\"이임차\"}]}";
    mockMvc
        .perform(jsonPatchAs(url + "/draft", patch, ownerCookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    mockMvc
        .perform(sign(id, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractStatus").value("SIGNED"))
        .andExpect(jsonPath("$.version").value(3));
    assertThat(text("SELECT lease_period_status FROM contract WHERE id = ?", id))
        .isEqualTo("IN_PROGRESS");
    assertThat(d90Count(id)).isEqualTo(1);
    JsonNode followups = getJson(url + "/followup-tasks", ownerCookie).get("items");
    assertThat(followups.size()).isEqualTo(1);
    assertThat(followups.get(0).get("taskType").asString()).isEqualTo("D90_FOLLOWUP");
    assertThat(followups.get(0).get("targetDate").asString()).isEqualTo(end.toString());
    assertThat(followups.get(0).get("submitted").booleanValue()).isFalse();
    assertThat(
            text(
                "SELECT status FROM task WHERE source_type = 'CONTRACT_MATURITY' AND source_id = ?",
                id))
        .isEqualTo("TODO");

    // 3. 재계약은 새 계약을 만들고 기존 계약의 상태와 버전을 그대로 둔다
    long beforeRenew = contractVersion(id);
    JsonNode renewed =
        AuthFlowSupport.json(
            mockMvc
                .perform(
                    jsonPostAs(
                        url + "/renew",
                        jeonse(ownerMembership, today(), end.plusDays(1), end.plusYears(2), ""),
                        ownerCookie))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.previousContractId").value(id))
                .andExpect(jsonPath("$.contractStatus").value("SIGNED"))
                .andReturn());
    long renewedId = renewed.get("contractId").longValue();
    assertThat(text("SELECT contract_status FROM contract WHERE id = ?", id)).isEqualTo("SIGNED");
    assertThat(text("SELECT lease_period_status FROM contract WHERE id = ?", id))
        .isEqualTo("IN_PROGRESS");
    assertThat(contractVersion(id)).isEqualTo(beforeRenew);
    assertThat(text("SELECT lease_period_status FROM contract WHERE id = ?", renewedId))
        .isEqualTo("BEFORE_START");
    assertThat(d90Count(renewedId)).isZero();

    // 4. 기존 계약을 별도로 정상 종료한다
    mockMvc
        .perform(
            jsonPatchAs(
                url + "/status",
                "{\"toStatus\":\"ENDED\",\"reason\":\"만기 정상 종료\",\"effectiveAt\":\""
                    + Instant.now().minusSeconds(60).atZone(KST).toOffsetDateTime()
                    + "\"}",
                ownerCookie,
                contractVersion(id)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contractStatus").value("ENDED"));
    JsonNode history = getJson(url + "/status-history", ownerCookie).get("items");
    boolean endedRecorded = false;
    for (JsonNode item : history) {
      if ("CONTRACT".equals(item.get("track").asString())
          && "ENDED".equals(item.get("toStatus").asString())
          && "SIGNED".equals(item.get("fromStatus").asString())) {
        endedRecorded = true;
      }
    }
    assertThat(endedRecorded).isTrue();
    assertThat(text("SELECT contract_status FROM contract WHERE id = ?", renewedId))
        .isEqualTo("SIGNED");

    // 5. D-90 배치는 새 계약(만기 2년 뒤)에 후속업무를 만들지 않고 기존 업무를 중복하지 않는다
    followupService.createDue();
    assertThat(d90Count(renewedId)).isZero();
    assertThat(d90Count(id)).isEqualTo(1);

    // 6. 기존 계약의 D-90 후속업무를 확인 처리한다
    long taskId =
        longValue(
            "SELECT id FROM contract_followup_task WHERE contract_id = ? AND task_type ="
                + " 'D90_FOLLOWUP'",
            id);
    long version = contractVersion(id);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/contract-followup-tasks/" + taskId,
                "{\"submitted\":true}",
                ownerCookie,
                version))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.submitted").value(true))
        .andExpect(jsonPath("$.version").value(version + 1));
    mockMvc
        .perform(authedGet(url + "/followup-tasks", ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].taskType").value("D90_FOLLOWUP"))
        .andExpect(jsonPath("$.items[0].submitted").value(true));
  }
}
