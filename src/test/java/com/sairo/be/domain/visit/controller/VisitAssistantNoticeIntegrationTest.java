package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class VisitAssistantNoticeIntegrationTest extends VisitOutcomeTestBase {

  private record DeputyVisit(
      TestData.Staff owner, TestData.Staff deputy, long candidateId, long itemId) {}

  private DeputyVisit deputyVisit() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    long officeId = owner.office().getId();
    long customer = insertCustomer(officeId, owner.membership().getId(), "이수요");
    long property = newProperty(cookieOf(owner));
    long candidate =
        insertCoordinationWithCandidate(
            new TestStaffRef(officeId, deputy.membership().getId()),
            customer,
            property,
            "CONFIRMED");
    long item =
        insertBusyItem(
            coordinationOfCandidate(candidate),
            candidate,
            officeId,
            today().plusDays(2),
            "10:00",
            "10:30",
            null);
    return new DeputyVisit(owner, deputy, candidate, item);
  }

  private ResultActions record(Cookie cookie, long itemId, String body) throws Exception {
    return mockMvc.perform(jsonPostAs(itemUrl(itemId) + "/assistant-notice", body, cookie));
  }

  private static String notified(String recipient, int minutesAgo, String extra) {
    return "{\"recipientType\":\""
        + recipient
        + "\",\"recipientName\":\"홍길동\",\"noticeMethod\":\"IN_PERSON\",\"notifiedAt\":\""
        + minutesAgo(minutesAgo)
        + "\""
        + extra
        + "}";
  }

  private ResultActions confirm(Cookie cookie, long noticeId, String body) throws Exception {
    return mockMvc.perform(
        jsonPostAs("/api/visit-assistant-notices/" + noticeId + "/confirm", body, cookie));
  }

  @Test
  void 담당_보조원이_고지를_기록하면_현황과_감독_이력에_나오고_대표가_한_번만_확인한다() throws Exception {
    DeputyVisit v = deputyVisit();
    Cookie deputyCookie = cookieOf(v.deputy());
    Cookie ownerCookie = cookieOf(v.owner());

    long noticeId =
        AuthFlowSupport.json(
                record(deputyCookie, v.itemId(), notified("CUSTOMER", 2, ""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scheduleItemId").value(v.itemId()))
                    .andExpect(jsonPath("$.recipientType").value("CUSTOMER"))
                    .andExpect(jsonPath("$.recordedAfterTheFact").value(false))
                    .andExpect(
                        jsonPath("$.recordedByMembershipId").value(v.deputy().membership().getId()))
                    .andExpect(jsonPath("$.confirmedAt").value(nullValue()))
                    .andReturn())
            .get("noticeId")
            .longValue();

    mockMvc
        .perform(authedGet(itemUrl(v.itemId()) + "/assistant-notices", deputyCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.requiredRecipients[0].recipientType").value("CUSTOMER"))
        .andExpect(jsonPath("$.requiredRecipients[0].recorded").value(true))
        .andExpect(jsonPath("$.requiredRecipients[1].recipientType").value("APPROVER"))
        .andExpect(jsonPath("$.requiredRecipients[1].candidatePropertyId").value(v.candidateId()))
        .andExpect(jsonPath("$.requiredRecipients[1].recorded").value(false));

    confirm(ownerCookie, noticeId, "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmedByMembershipId").value(v.owner().membership().getId()))
        .andExpect(jsonPath("$.confirmedAt").isNotEmpty())
        .andExpect(jsonPath("$.supervisorAdditionalNotice").value(nullValue()))
        .andExpect(jsonPath("$.supervisorAdditionalNoticedAt").value(nullValue()));
    assertError(confirm(ownerCookie, noticeId, "{}"), ErrorCode.INVALID_TRANSITION);

    mockMvc
        .perform(authedGet("/api/visit-assistant-notices", ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].noticeId").value(noticeId))
        .andExpect(
            jsonPath("$.items[0].assistantMembershipId").value(v.deputy().membership().getId()));
    mockMvc
        .perform(authedGet("/api/visit-assistant-notices?confirmed=false", ownerCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 감독_확인에_추가_안내를_보내면_저장하고_안내_시각을_확인_시각으로_채운다() throws Exception {
    DeputyVisit v = deputyVisit();
    long noticeId =
        AuthFlowSupport.json(
                record(cookieOf(v.deputy()), v.itemId(), notified("CUSTOMER", 2, ""))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.supervisorAdditionalNotice").value(nullValue()))
                    .andReturn())
            .get("noticeId")
            .longValue();

    var confirmed =
        AuthFlowSupport.json(
            confirm(
                    cookieOf(v.owner()),
                    noticeId,
                    "{\"supervisorAdditionalNotice\":\"현장에서 한 번 더 안내할 것\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supervisorAdditionalNotice").value("현장에서 한 번 더 안내할 것"))
                .andExpect(jsonPath("$.supervisorAdditionalNoticedAt").isNotEmpty())
                .andReturn());
    assertThat(confirmed.get("supervisorAdditionalNoticedAt").asString())
        .isEqualTo(confirmed.get("confirmedAt").asString());

    var row =
        jdbcTemplate.queryForMap(
            "SELECT supervisor_additional_notice, supervisor_additional_noticed_at = confirmed_at"
                + " AS same_time FROM visit_assistant_notice_log WHERE id = ?",
            noticeId);
    assertThat(row.get("supervisor_additional_notice")).isEqualTo("현장에서 한 번 더 안내할 것");
    assertThat(row.get("same_time")).isEqualTo(true);
  }

  @Test
  void 고지_입력은_시각과_미고지_사유_중_하나이고_사후_입력과_미고지에는_후속_조치가_필요하다() throws Exception {
    DeputyVisit v = deputyVisit();
    Cookie deputyCookie = cookieOf(v.deputy());
    String future = OffsetDateTime.now(KST).plusHours(1).withNano(0).toString();

    record(
            deputyCookie,
            v.itemId(),
            "{\"recipientType\":\"CUSTOMER\",\"recipientName\":\"홍길동\",\"noticeMethod\":\"PHONE\"}")
        .andExpect(status().isBadRequest());
    record(
            deputyCookie,
            v.itemId(),
            notified("CUSTOMER", 2, ",\"missingExceptionReason\":\"연락 두절\""))
        .andExpect(status().isBadRequest());
    record(
            deputyCookie,
            v.itemId(),
            "{\"recipientType\":\"CUSTOMER\",\"recipientName\":\"홍길동\",\"noticeMethod\":\"PHONE\","
                + "\"notifiedAt\":\""
                + future
                + "\"}")
        .andExpect(status().isBadRequest());
    record(deputyCookie, v.itemId(), notified("NOBODY", 2, "")).andExpect(status().isBadRequest());
    record(deputyCookie, v.itemId(), notified("APPROVER", 40, ""))
        .andExpect(status().isBadRequest());
    record(
            deputyCookie,
            v.itemId(),
            "{\"recipientType\":\"APPROVER\",\"recipientName\":\"김현소\",\"noticeMethod\":\"PHONE\","
                + "\"missingExceptionReason\":\"연락 두절\"}")
        .andExpect(status().isBadRequest());

    record(deputyCookie, v.itemId(), notified("APPROVER", 40, ",\"followupAction\":\"현장에서 다시 안내\""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.recordedAfterTheFact").value(true))
        .andExpect(jsonPath("$.followupAction").value("현장에서 다시 안내"));
    record(
            deputyCookie,
            v.itemId(),
            "{\"recipientType\":\"OTHER\",\"recipientName\":\"관리인\",\"noticeMethod\":\"IN_PERSON\","
                + "\"missingExceptionReason\":\"연락 두절\",\"followupAction\":\"문자로 안내\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.notifiedAt").value(nullValue()))
        .andExpect(jsonPath("$.missingExceptionReason").value("연락 두절"));
  }

  @Test
  void 고지_기록과_조회와_확인의_권한과_다른_사무소를_검사한다() throws Exception {
    DeputyVisit v = deputyVisit();
    Cookie deputyCookie = cookieOf(v.deputy());
    Cookie ownerCookie = cookieOf(v.owner());
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    var otherDeputy = staffIn(v.owner().office(), OfficeMembershipRole.DEPUTY);
    var agent = staffIn(v.owner().office(), OfficeMembershipRole.AGENT);
    String body = notified("CUSTOMER", 2, "");

    assertError(record(null, v.itemId(), body), ErrorCode.UNAUTHENTICATED);
    assertError(record(cookieOf(stranger), v.itemId(), body), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(record(ownerCookie, v.itemId(), body), ErrorCode.FORBIDDEN);
    assertError(record(cookieOf(otherDeputy), v.itemId(), body), ErrorCode.FORBIDDEN);
    SelectionFixture ownerManaged = confirmed();
    assertError(
        record(ownerManaged.cookie(), scheduledItem(ownerManaged.coordinationId()), body),
        ErrorCode.INVALID_TRANSITION);

    String listUrl = itemUrl(v.itemId()) + "/assistant-notices";
    assertError(mockMvc.perform(authedGet(listUrl, null)), ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet(listUrl, cookieOf(stranger))), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(mockMvc.perform(authedGet(listUrl, cookieOf(otherDeputy))), ErrorCode.FORBIDDEN);
    mockMvc.perform(authedGet(listUrl, ownerCookie)).andExpect(status().isOk());

    long noticeId =
        AuthFlowSupport.json(
                record(deputyCookie, v.itemId(), body).andExpect(status().isCreated()).andReturn())
            .get("noticeId")
            .longValue();
    assertError(confirm(null, noticeId, "{}"), ErrorCode.UNAUTHENTICATED);
    assertError(confirm(deputyCookie, noticeId, "{}"), ErrorCode.FORBIDDEN);
    assertError(confirm(cookieOf(stranger), noticeId, "{}"), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        confirm(ownerCookie, noticeId, "{\"supervisorAdditionalNotice\":123}"),
        ErrorCode.INVALID_REQUEST);
    assertError(
        confirm(
            ownerCookie, noticeId, "{\"supervisorAdditionalNotice\":\"" + "가".repeat(501) + "\"}"),
        ErrorCode.INVALID_REQUEST);
    confirm(cookieOf(agent), noticeId, "{}").andExpect(status().isOk());

    assertError(
        mockMvc.perform(authedGet("/api/visit-assistant-notices", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet("/api/visit-assistant-notices", cookieOf(agent))),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(authedGet("/api/visit-assistant-notices", deputyCookie)),
        ErrorCode.FORBIDDEN);
    mockMvc
        .perform(authedGet("/api/visit-assistant-notices", cookieOf(stranger)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc
        .perform(authedGet("/api/visit-assistant-notices?from=2026-13-01", ownerCookie))
        .andExpect(status().isBadRequest());
  }
}
