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
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class VisitOutcomeIntegrationTest extends VisitOutcomeTestBase {

  @Test
  void 방문_완료를_기록하면_이력이_남고_평가_기한을_알리며_조율이_방문_완료로_집계된다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    long version = coordinationVersion(s.coordinationId());

    patchResult(s.cookie(), item, completedBody(30), version)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itemId").value(item))
        .andExpect(jsonPath("$.result").value("COMPLETED"))
        .andExpect(jsonPath("$.correctionCount").value(0))
        .andExpect(jsonPath("$.coordinationStatus").value("VISIT_COMPLETED"))
        .andExpect(jsonPath("$.affectedItems.length()").value(0))
        .andExpect(jsonPath("$.reviewWindowEndsAt").isNotEmpty());

    assertThat(itemResult(item)).isEqualTo("COMPLETED");
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("VISIT_COMPLETED");
    assertThat(coordinationVersion(s.coordinationId())).isGreaterThan(version);
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item_result_history WHERE schedule_item_id = ?"
                    + " AND result = 'COMPLETED' AND previous_result = 'SCHEDULED' AND"
                    + " supersedes_history_id IS NULL",
                item))
        .isEqualTo(1L);
  }

  @Test
  void 정정은_사유가_필요하고_이전_이력을_대체하며_조율_집계를_다시_계산한다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    recordResult(s, item, completedBody(30)).andExpect(status().isOk());

    recordResult(s, item, "{\"result\":\"NO_SHOW\"}").andExpect(status().isBadRequest());
    recordResult(s, item, "{\"result\":\"NO_SHOW\",\"correctionReason\":\"고객이 오지 않았다\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("NO_SHOW"))
        .andExpect(jsonPath("$.actualVisitedAt").value(nullValue()))
        .andExpect(jsonPath("$.reviewWindowEndsAt").value(nullValue()))
        .andExpect(jsonPath("$.correctionCount").value(1))
        .andExpect(jsonPath("$.coordinationStatus").value("VISIT_INCOMPLETE"));
    assertError(
        recordResult(s, item, "{\"result\":\"CANCELLED\",\"correctionReason\":\"취소로 바꾼다\"}"),
        ErrorCode.INVALID_TRANSITION);

    mockMvc
        .perform(authedGet(itemUrl(item) + "/result-history", s.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].previousResult").value("COMPLETED"))
        .andExpect(jsonPath("$.items[0].newResult").value("NO_SHOW"))
        .andExpect(jsonPath("$.items[0].correctionReason").value("고객이 오지 않았다"))
        .andExpect(jsonPath("$.items[0].recordedByName").isNotEmpty())
        .andExpect(jsonPath("$.items[1].previousResult").value("SCHEDULED"));
  }

  @Test
  void 결과_기록_입력과_버전과_권한을_검사한다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    long version = coordinationVersion(s.coordinationId());
    String future = OffsetDateTime.now(KST).plusHours(1).withNano(0).toString();

    patchResult(s.cookie(), item, "{\"result\":\"COMPLETED\"}", version)
        .andExpect(status().isBadRequest());
    patchResult(
            s.cookie(),
            item,
            "{\"result\":\"COMPLETED\",\"actualVisitedAt\":\"" + future + "\"}",
            version)
        .andExpect(status().isBadRequest());
    patchResult(
            s.cookie(),
            item,
            "{\"result\":\"CANCELLED\",\"cancelReasonCode\":\"CHANGE_REPLACED\"}",
            version)
        .andExpect(status().isBadRequest());
    patchResult(s.cookie(), item, "{\"result\":\"SCHEDULED\"}", version)
        .andExpect(status().isBadRequest());
    patchResult(s.cookie(), item, completedBody(10), null).andExpect(status().isBadRequest());
    assertError(
        patchResult(s.cookie(), item, completedBody(10), version + 5), ErrorCode.VERSION_CONFLICT);
    assertError(
        patchResult(s.cookie(), 987654L, completedBody(10), version), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(patchResult(null, item, completedBody(10), version), ErrorCode.UNAUTHENTICATED);
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    assertError(
        patchResult(cookieOf(stranger), item, completedBody(10), version),
        ErrorCode.RESOURCE_NOT_FOUND);
    var deputy = staffIn(s.base().owner().office(), OfficeMembershipRole.DEPUTY);
    assertError(
        patchResult(cookieOf(deputy), item, completedBody(10), version), ErrorCode.FORBIDDEN);
    assertThat(itemResult(item)).isEqualTo("SCHEDULED");

    assertError(
        mockMvc.perform(authedGet(itemUrl(item) + "/result-history", null)),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(authedGet(itemUrl(item) + "/result-history", cookieOf(stranger))),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(authedGet(itemUrl(item) + "/result-history", cookieOf(deputy))),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 방문_하나를_취소하면_확정_신청이_취소되고_빈자리_알림을_남기며_다시_입력할_수_없다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    long closed =
        insertRequestRow(
            s.base().ref(),
            s.candidateId(),
            s.base().propertyId(),
            s.base().approvalId(),
            s.base().owner().office().getId(),
            "TIME_CLOSED",
            "'" + visitDay() + "T10:10:00+09:00'::timestamptz");

    recordResult(s, item, "{\"result\":\"CANCELLED\",\"cancelReasonCode\":\"CUSTOMER_REASON\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("CANCELLED"))
        .andExpect(jsonPath("$.coordinationStatus").value("VISIT_INCOMPLETE"));

    assertThat(requestStatus(s.requestId())).isEqualTo("CANCELLED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_status_history WHERE request_id = ? AND"
                    + " to_status = 'CANCELLED' AND source = 'BROKER' AND actor_membership_id ="
                    + " ?",
                s.requestId(),
                s.base().owner().membership().getId()))
        .isEqualTo(1L);
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item_result_history WHERE schedule_item_id = ?"
                    + " AND cancel_reason_code = 'CUSTOMER_REASON'",
                item))
        .isEqualTo(1L);
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'SLOT_REOPENED'",
                closed))
        .isEqualTo(1L);
    assertError(recordResult(s, item, completedBody(5)), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 조율_건을_결과_기록_전에_취소하면_취소_상태가_되고_고객_링크가_폐기된다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());

    cancel(s.cookie(), s.coordinationId(), "{\"reason\":\"고객이 계약을 접었다\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.scheduleItems[0].result").value("CANCELLED"))
        .andExpect(jsonPath("$.affectedItems.length()").value(0))
        .andExpect(jsonPath("$.candidateProperties.length()").value(1))
        .andExpect(jsonPath("$.officeId").value(s.base().owner().office().getId()));

    assertThat(itemResult(item)).isEqualTo("CANCELLED");
    assertThat(requestStatus(s.requestId())).isEqualTo("CANCELLED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT cancel_reason FROM visit_coordination WHERE id = ?",
                String.class,
                s.coordinationId()))
        .isEqualTo("고객이 계약을 접었다");
    assertThat(
            count(
                "SELECT count(*) FROM visit_customer_link WHERE coordination_id = ? AND revoked_at"
                    + " IS NULL",
                s.coordinationId()))
        .isZero();
    assertError(cancel(s.cookie(), s.coordinationId(), "{}"), ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 신청_진행_중에_취소하면_대기_신청이_철회로_끝난다() throws Exception {
    RequestFixture f = requestFixture();
    long requestId = submit(f);

    cancel(f.cookie(), f.coordinationId(), "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.scheduleItems.length()").value(0));
    assertThat(requestStatus(requestId)).isEqualTo("WITHDRAWN");
  }

  @Test
  void 일부_결과_기록_뒤_취소는_남은_예정만_취소하고_방문_완료로_집계한다() throws Exception {
    TwoVisits two = twoConfirmed();
    long coordination = two.base().coordinationId();
    assertThat(coordinationStatus(coordination)).isEqualTo("CONFIRMED");
    patchResult(
            two.base().cookie(),
            two.firstItemId(),
            completedBody(20),
            coordinationVersion(coordination))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coordinationStatus").value("CONFIRMED"));

    cancel(two.base().cookie(), coordination, "{\"reason\":\"나머지는 취소\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VISIT_COMPLETED"));

    assertThat(itemResult(two.firstItemId())).isEqualTo("COMPLETED");
    assertThat(itemResult(two.secondItemId())).isEqualTo("CANCELLED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT cancel_reason FROM visit_coordination WHERE id = ?",
                String.class,
                coordination))
        .isNull();
  }

  @Test
  void 취소_재조율_철회_접수는_If_Match와_권한과_다른_사무소를_검사한다() throws Exception {
    SelectionFixture s = confirmed();
    long version = coordinationVersion(s.coordinationId());
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    var deputy = staffIn(s.base().owner().office(), OfficeMembershipRole.DEPUTY);
    String revocation =
        "{\"approvalId\":"
            + s.base().approvalId()
            + ",\"revokedAt\":\""
            + minutesAgo(1)
            + "\",\"revocationReason\":\"점유자 요청\"}";
    for (String path : new String[] {"/cancel", "/reschedule", "/access-revocations"}) {
      String url = coordinationUrl(s.coordinationId()) + path;
      String body = path.equals("/access-revocations") ? revocation : "{}";
      postVersioned(url, body, s.cookie(), null).andExpect(status().isBadRequest());
      assertError(postVersioned(url, body, null, version), ErrorCode.UNAUTHENTICATED);
      assertError(
          postVersioned(url, body, cookieOf(stranger), version), ErrorCode.RESOURCE_NOT_FOUND);
      assertError(postVersioned(url, body, cookieOf(deputy), version), ErrorCode.FORBIDDEN);
      assertError(postVersioned(url, body, s.cookie(), version + 7), ErrorCode.VERSION_CONFLICT);
    }
    postVersioned(
            coordinationUrl(s.coordinationId()) + "/cancel", "{\"reason\":1}", s.cookie(), version)
        .andExpect(status().isBadRequest());
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("CONFIRMED");
  }

  @Test
  void 재조율_필요로_바꾸면_영향_방문과_대안_후보를_돌려주고_상세도_같은_대안을_보인다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());

    JsonNode response =
        AuthFlowSupport.json(
            postVersioned(
                    coordinationUrl(s.coordinationId()) + "/reschedule",
                    "{\"reason\":\"점유자가 바뀌었다\"}",
                    s.cookie(),
                    coordinationVersion(s.coordinationId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESCHEDULE_REQUIRED"))
                .andExpect(jsonPath("$.scheduleItems[0].state").value("RESCHEDULE_REQUIRED"))
                .andExpect(jsonPath("$.affectedItems[0].scheduleItemId").value(item))
                .andExpect(jsonPath("$.affectedItems[0].reason").value("NO_PERMISSION"))
                .andExpect(jsonPath("$.fixedScheduleItemIds.length()").value(0))
                .andExpect(jsonPath("$.alternatives.length()").value(3))
                .andExpect(jsonPath("$.alternatives[0].checks.travel").value("PASSED"))
                .andExpect(jsonPath("$.noAlternativeScheduleItemIds.length()").value(0))
                .andReturn());
    assertThat(
            OffsetDateTime.parse(response.get("alternatives").get(0).get("startAt").asString())
                .toInstant())
        .isEqualTo(at(visitDay(), "10:30"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reschedule_trigger_type FROM visit_coordination WHERE id = ?",
                String.class,
                s.coordinationId()))
        .isEqualTo("OCCUPANT_CHANGED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_customer_link WHERE coordination_id = ? AND revoked_at"
                    + " IS NULL",
                s.coordinationId()))
        .isZero();
    mockMvc
        .perform(authedGet(coordinationUrl(s.coordinationId()), s.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rescheduleAlert.triggerType").value("OCCUPANT_CHANGED"))
        .andExpect(jsonPath("$.scheduleItems[0].state").value("RESCHEDULE_REQUIRED"))
        .andExpect(jsonPath("$.alternatives.length()").value(3));

    assertError(
        postVersioned(
            coordinationUrl(s.coordinationId()) + "/reschedule",
            "{}",
            s.cookie(),
            coordinationVersion(s.coordinationId())),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 재조율_중_변경_신청이_확정되면_기존_방문을_대체하고_확정_완료로_돌아간다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    postVersioned(
            coordinationUrl(s.coordinationId()) + "/reschedule",
            "{}",
            s.cookie(),
            coordinationVersion(s.coordinationId()))
        .andExpect(status().isOk());

    long changeId =
        AuthFlowSupport.json(
                mockMvc
                    .perform(
                        jsonPostAs(
                            itemUrl(item) + "/change-requests",
                            "{\"requestedStartAt\":\""
                                + visitDay()
                                + "T10:15:00+09:00\",\"requestedEndAt\":\""
                                + visitDay()
                                + "T10:45:00+09:00\",\"expectedDurationMinutes\":30}",
                            s.cookie()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.requestKind").value("CHANGE"))
                    .andExpect(jsonPath("$.replacesScheduleItemId").value(item))
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andReturn())
            .get("requestId")
            .longValue();
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("RESCHEDULE_REQUIRED");

    selectNow(s.token(), changeId)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestStatus").value("CONFIRMED"));

    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("CONFIRMED");
    assertThat(itemResult(item)).isEqualTo("CANCELLED");
    assertThat(requestStatus(s.requestId())).isEqualTo("CANCELLED");
    assertThat(
            count(
                "SELECT count(*) FROM visit_schedule_item_result_history WHERE schedule_item_id = ?"
                    + " AND cancel_reason_code = 'CHANGE_REPLACED' AND recorded_by_membership_id ="
                    + " ?",
                item,
                s.base().owner().membership().getId()))
        .isEqualTo(1L);
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_status_history WHERE request_id = ? AND"
                    + " to_status = 'CANCELLED' AND source = 'SYSTEM' AND actor_membership_id IS"
                    + " NULL",
                s.requestId()))
        .isEqualTo(1L);
    long replacement = scheduledItem(s.coordinationId());
    assertThat(replacement).isNotEqualTo(item);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT confirmed_request_id FROM visit_schedule_item WHERE id = ?",
                Long.class,
                replacement))
        .isEqualTo(changeId);
  }

  @Test
  void 변경_신청은_조율_상태를_바꾸지_않고_입력과_권한과_대상_상태를_검사한다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    String url = itemUrl(item) + "/change-requests";
    String body =
        "{\"requestedStartAt\":\""
            + visitDay()
            + "T11:00:00+09:00\",\"requestedEndAt\":\""
            + visitDay()
            + "T11:30:00+09:00\",\"expectedDurationMinutes\":30}";

    mockMvc
        .perform(jsonPostAs(url, "{\"candidatePropertyId\":1}", s.cookie()))
        .andExpect(status().isBadRequest());
    assertError(mockMvc.perform(jsonPostAs(url, body, null)), ErrorCode.UNAUTHENTICATED);
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    assertError(
        mockMvc.perform(jsonPostAs(url, body, cookieOf(stranger))), ErrorCode.RESOURCE_NOT_FOUND);
    var deputy = staffIn(s.base().owner().office(), OfficeMembershipRole.DEPUTY);
    assertError(mockMvc.perform(jsonPostAs(url, body, cookieOf(deputy))), ErrorCode.FORBIDDEN);

    mockMvc.perform(jsonPostAs(url, body, s.cookie())).andExpect(status().isCreated());
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("CONFIRMED");
    assertError(
        mockMvc.perform(jsonPostAs(url, body, s.cookie())),
        ErrorCode.VISIT_REQUEST_DUPLICATE_ACTIVE);

    recordResult(s, item, completedBody(5)).andExpect(status().isOk());
    assertError(
        mockMvc.perform(jsonPostAs(url, body, s.cookie())), ErrorCode.VISIT_REQUEST_INVALID_STATE);
  }

  @Test
  void 출입승인_철회를_접수하면_재조율_경보가_되고_후속_접수는_최초_사실을_돌려준다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    String first = minutesAgo(3);
    String url = coordinationUrl(s.coordinationId()) + "/access-revocations";

    JsonNode response =
        AuthFlowSupport.json(
            postVersioned(
                    url,
                    "{\"approvalId\":"
                        + s.base().approvalId()
                        + ",\"revokedAt\":\""
                        + first
                        + "\",\"revocationReason\":\"점유자 요청\"}",
                    s.cookie(),
                    coordinationVersion(s.coordinationId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(s.base().approvalId()))
                .andExpect(jsonPath("$.status").value("RESCHEDULE_REQUIRED"))
                .andExpect(jsonPath("$.revokedByUserId").isNumber())
                .andReturn());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT reschedule_approval_id FROM visit_coordination WHERE id = ?",
                Long.class,
                s.coordinationId()))
        .isEqualTo(s.base().approvalId());
    assertThat(
            count(
                "SELECT count(*) FROM visit_request_notice WHERE request_id = ? AND notice_type ="
                    + " 'CONDITION_CHANGE_IMPACT'",
                s.requestId()))
        .isEqualTo(1L);
    mockMvc
        .perform(authedGet(coordinationUrl(s.coordinationId()), s.cookie()))
        .andExpect(jsonPath("$.scheduleItems[0].state").value("RESCHEDULE_REQUIRED"));

    postVersioned(
            url,
            "{\"approvalId\":"
                + s.base().approvalId()
                + ",\"revokedAt\":\""
                + minutesAgo(1)
                + "\",\"revocationReason\":\"다른 사유\"}",
            s.cookie(),
            coordinationVersion(s.coordinationId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revokedAt").value(response.get("revokedAt").asString()));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT revocation_reason FROM property_access_approval WHERE id = ?",
                String.class,
                s.base().approvalId()))
        .isEqualTo("점유자 요청");

    recordResult(s, item, "{\"result\":\"CANCELLED\",\"cancelReasonCode\":\"APPROVER_REASON\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coordinationStatus").value("VISIT_INCOMPLETE"));
  }

  @Test
  void 철회_접수_입력과_대상_출입승인을_검사한다() throws Exception {
    SelectionFixture s = confirmed();
    String url = coordinationUrl(s.coordinationId()) + "/access-revocations";
    long version = coordinationVersion(s.coordinationId());
    String future = OffsetDateTime.now(KST).plusHours(1).withNano(0).toString();

    postVersioned(
            url,
            "{\"approvalId\":"
                + s.base().approvalId()
                + ",\"revokedAt\":\""
                + future
                + "\",\"revocationReason\":\"사유\"}",
            s.cookie(),
            version)
        .andExpect(status().isBadRequest());
    postVersioned(url, "{\"approvalId\":" + s.base().approvalId() + "}", s.cookie(), version)
        .andExpect(status().isBadRequest());
    long otherProperty = newProperty(s.cookie());
    long otherApproval = registerApproval(s.cookie(), otherProperty, null, -1, 30);
    assertError(
        postVersioned(
            url,
            "{\"approvalId\":"
                + otherApproval
                + ",\"revokedAt\":\""
                + minutesAgo(1)
                + "\",\"revocationReason\":\"사유\"}",
            s.cookie(),
            version),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(coordinationStatus(s.coordinationId())).isEqualTo("CONFIRMED");
  }

  @Test
  void 취소된_조율만_새_기간으로_복제하고_링크는_복제하지_않는다() throws Exception {
    SelectionFixture s = confirmed();
    String url = coordinationUrl(s.coordinationId()) + "/clone";
    String body =
        "{\"periodStartDate\":\""
            + today().plusDays(1)
            + "\",\"periodEndDate\":\""
            + today().plusDays(8)
            + "\"}";
    assertError(mockMvc.perform(jsonPostAs(url, body, s.cookie())), ErrorCode.INVALID_TRANSITION);
    cancel(s.cookie(), s.coordinationId(), "{}").andExpect(status().isOk());

    JsonNode cloned =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs(url, body, s.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TIME_COLLECTING"))
                .andExpect(jsonPath("$.candidateProperties.length()").value(1))
                .andExpect(
                    jsonPath("$.candidateProperties[0].propertyId").value(s.base().propertyId()))
                .andExpect(jsonPath("$.officeId").value(s.base().owner().office().getId()))
                .andReturn());
    long newId = cloned.get("coordinationId").longValue();
    assertThat(newId).isNotEqualTo(s.coordinationId());
    assertThat(count("SELECT count(*) FROM visit_customer_link WHERE coordination_id = ?", newId))
        .isZero();

    mockMvc
        .perform(
            jsonPostAs(
                url,
                "{\"periodStartDate\":\""
                    + today()
                    + "\",\"periodEndDate\":\""
                    + today().plusDays(40)
                    + "\"}",
                s.cookie()))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("PERIOD_TOO_LONG"));
    mockMvc.perform(jsonPostAs(url, "{}", s.cookie())).andExpect(status().isBadRequest());
    assertError(mockMvc.perform(jsonPostAs(url, body, null)), ErrorCode.UNAUTHENTICATED);
    var stranger = newStaff(OfficeMembershipRole.OWNER);
    assertError(
        mockMvc.perform(jsonPostAs(url, body, cookieOf(stranger))), ErrorCode.RESOURCE_NOT_FOUND);
  }
}
