package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class PublicConfirmedScheduleIntegrationTest extends VisitOutcomeTestBase {

  private ResultActions customerConfirmed(String token) throws Exception {
    return mockMvc.perform(get("/api/public/customer-visit-links/" + token + "/confirmed"));
  }

  private ResultActions approverConfirmed(String token) throws Exception {
    return mockMvc.perform(
        get("/api/public/approver-property-links/" + token + "/confirmed-schedules"));
  }

  @Test
  void 고객은_같은_토큰으로_확정_일정을_읽고_취소되면_재확인_안내로_바뀐다() throws Exception {
    RequestFixture f = requestFixture();
    String customerToken = reissueCustomerToken(f.cookie(), f.coordinationId());
    long requestId = submit(f);
    assertError(customerConfirmed(customerToken), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    String approverToken = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());
    selectNow(approverToken, requestId).andExpect(status().isOk());

    customerConfirmed(customerToken)
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.state").value("VALID"))
        .andExpect(jsonPath("$.scheduleVersion").value(1))
        .andExpect(jsonPath("$.managerLegalRole").value("REPRESENTATIVE_AGENT"))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].sortOrder").value(1))
        .andExpect(jsonPath("$.items[0].travelDurationMinutes").value(nullValue()))
        .andExpect(jsonPath("$.items[0].propertyAddress").isString());

    cancel(f.cookie(), f.coordinationId(), "{}").andExpect(status().isOk());
    customerConfirmed(customerToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CANCELLED"))
        .andExpect(jsonPath("$.items.length()").value(0));
    assertError(customerConfirmed("no-such-token"), ErrorCode.PUBLIC_LINK_NOT_FOUND);
  }

  @Test
  void 마지막_방문_종료_후_24시간이_지나면_고객_확정_조회가_만료된다() throws Exception {
    RequestFixture f = requestFixture();
    String customerToken = reissueCustomerToken(f.cookie(), f.coordinationId());
    long requestId = submit(f);
    selectNow(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()), requestId)
        .andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET scheduled_start_at = now() - interval '3 days',"
            + " scheduled_end_at = now() - interval '3 days' + interval '30 minutes' WHERE"
            + " coordination_id = ?",
        f.coordinationId());

    assertError(customerConfirmed(customerToken), ErrorCode.PUBLIC_LINK_EXPIRED);
  }

  @Test
  void 승인자는_본인_매물의_확정_일정을_고객_정보_없이_읽고_공동_승인자도_같은_일정을_본다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());

    String body =
        approverConfirmed(s.token())
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", containsString("no-store")))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].scheduleItemId").value(item))
            .andExpect(jsonPath("$.items[0].result").value("SCHEDULED"))
            .andExpect(jsonPath("$.items[0].state").value("VALID"))
            .andExpect(jsonPath("$.items[0].confirmedRequestId").value(s.requestId()))
            .andExpect(jsonPath("$.items[0].applicantRelation").value("OWNER_OFFICE"))
            .andExpect(jsonPath("$.items[0].applicantBrokerName").isNotEmpty())
            .andExpect(jsonPath("$.officeName").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("이수요");

    long coApproval = registerApproval(s.cookie(), s.base().propertyId(), "CO_APPROVER", -1, 30);
    String coToken = issueApproverToken(s.cookie(), s.base().propertyId(), coApproval);
    approverConfirmed(coToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].scheduleItemId").value(item));

    postVersioned(
            coordinationUrl(s.coordinationId()) + "/reschedule",
            "{}",
            s.cookie(),
            coordinationVersion(s.coordinationId()))
        .andExpect(status().isOk());
    approverConfirmed(s.token())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].state").value("RESCHEDULE_REQUIRED"));

    issueApproverToken(s.cookie(), s.base().propertyId(), s.base().approvalId());
    assertError(approverConfirmed(s.token()), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(approverConfirmed("no-such-token"), ErrorCode.PUBLIC_LINK_NOT_FOUND);
  }
}
