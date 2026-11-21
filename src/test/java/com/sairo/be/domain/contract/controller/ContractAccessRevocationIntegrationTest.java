package com.sairo.be.domain.contract.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

/** 구성원 접근을 정지하면 이미 발급된 세션도 바로 계약 조회를 못 하고 해제하면 같은 세션으로 다시 된다. */
@IntegrationTest
class ContractAccessRevocationIntegrationTest extends ContractIntegrationTestBase {

  @Test
  void 접근을_정지하면_같은_세션의_계약_조회와_변경이_바로_거부되고_해제하면_새_세션으로_다시_허용된다() throws Exception {
    long contractId = createJeonse(property, today(), today().plusYears(2));
    TestData.Staff agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie agentCookie = cookieOf(agent);
    String detail = "/api/contracts/" + contractId;
    mockMvc.perform(authedGet(detail, agentCookie)).andExpect(status().isOk());

    mockMvc
        .perform(
            jsonPostAs(
                "/api/memberships/" + agent.membership().getId() + "/suspend",
                "{\"reason\":\"점검\"}",
                ownerCookie))
        .andExpect(status().isOk());

    int read =
        mockMvc.perform(authedGet(detail, agentCookie)).andReturn().getResponse().getStatus();
    int list =
        mockMvc
            .perform(authedGet("/api/contracts", agentCookie))
            .andReturn()
            .getResponse()
            .getStatus();
    int write =
        mockMvc
            .perform(jsonPostAs(detail + "/fees", "{}", agentCookie))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(read).isIn(401, 403, 404);
    assertThat(list).isIn(401, 403, 404);
    assertThat(write).isIn(401, 403, 404);
    assertThat(longValue("SELECT count(*) FROM contract_fee")).isZero();

    mockMvc
        .perform(
            jsonPostAs(
                "/api/memberships/" + agent.membership().getId() + "/reinstate", "", ownerCookie))
        .andExpect(status().isOk());
    mockMvc.perform(authedGet(detail, cookieOf(agent))).andExpect(status().isOk());
  }
}
