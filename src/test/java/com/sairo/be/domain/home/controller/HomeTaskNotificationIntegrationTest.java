package com.sairo.be.domain.home.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.StubNotificationChannelSender;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class HomeTaskNotificationIntegrationTest extends VisitOutcomeTestBase {

  @Autowired private StubNotificationChannelSender channelSender;

  @BeforeEach
  void clearDeliveries() {
    channelSender.clear();
  }

  private static String taskBody(long assignee, String title, LocalDate due) {
    return "{\"title\":\""
        + title
        + "\",\"assigneeMembershipId\":"
        + assignee
        + ",\"dueDate\":\""
        + due
        + "\"}";
  }

  private JsonNode createTask(Cookie cookie, long assignee, String title, LocalDate due)
      throws Exception {
    return AuthFlowSupport.json(
        mockMvc
            .perform(jsonPostAs("/api/tasks", taskBody(assignee, title, due), cookie))
            .andExpect(status().isCreated())
            .andReturn());
  }

  private long insertNotification(Long officeId, long userId, String type, String target, Long id) {
    Long notificationId =
        jdbcTemplate.queryForObject(
            "INSERT INTO notification (office_id, recipient_user_id, notification_type, message,"
                + " target_type, target_id) VALUES (?, ?, ?, '알림', ?, ?) RETURNING id",
            Long.class,
            officeId,
            userId,
            type,
            target,
            id);
    return notificationId == null ? -1 : notificationId;
  }

  private long visitTaskOf(long itemId) {
    return count("SELECT id FROM task WHERE source_type = 'VISIT' AND source_id = ?", itemId);
  }

  private String taskColumn(String column, long taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT " + column + "::text FROM task WHERE id = ?", String.class, taskId);
  }

  private Map<String, Function<Cookie, MockHttpServletRequestBuilder>> endpoints(
      long taskId, long notificationId) {
    Map<String, Function<Cookie, MockHttpServletRequestBuilder>> map = new LinkedHashMap<>();
    map.put("GET /api/home", c -> authedGet("/api/home", c));
    map.put("GET /api/tasks", c -> authedGet("/api/tasks", c));
    map.put("POST /api/tasks", c -> jsonPostAs("/api/tasks", taskBody(1, "업무", today()), c));
    map.put(
        "PATCH /api/tasks/{id}",
        c -> jsonPatchAs("/api/tasks/" + taskId, "{\"memo\":\"메모\"}", c, 1));
    map.put("DELETE /api/tasks/{id}", c -> deleteAs("/api/tasks/" + taskId, c));
    map.put("GET /api/notifications", c -> authedGet("/api/notifications", c));
    map.put(
        "POST /api/notifications/{id}/read",
        c -> jsonPostAs("/api/notifications/" + notificationId + "/read", "", c));
    return map;
  }

  @Test
  void 로그인이_없으면_401이고_소속이_없으면_403이다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    long taskId =
        createTask(cookieOf(owner), owner.membership().getId(), "업무", today())
            .get("taskId")
            .longValue();
    long notificationId =
        insertNotification(owner.office().getId(), owner.user().getId(), "NOTICE", "NONE", null);
    Cookie plain = kakaoSessionFor(saveEmailUser("home-plain@example.com", "010-3434-5656"));
    for (var entry : endpoints(taskId, notificationId).entrySet()) {
      assertError(mockMvc.perform(entry.getValue().apply(null)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(entry.getValue().apply(plain)), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 다른_사무소의_업무와_다른_사람의_알림은_404다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    long taskId =
        createTask(cookieOf(owner), owner.membership().getId(), "업무", today())
            .get("taskId")
            .longValue();
    long notificationId =
        insertNotification(owner.office().getId(), owner.user().getId(), "NOTICE", "NONE", null);
    Cookie other = cookieOf(newStaff(OfficeMembershipRole.OWNER));

    assertError(
        mockMvc.perform(jsonPatchAs("/api/tasks/" + taskId, "{\"memo\":\"메모\"}", other, 1)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(deleteAs("/api/tasks/" + taskId, other)), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs("/api/notifications/" + notificationId + "/read", "", other)),
        ErrorCode.RESOURCE_NOT_FOUND);
    mockMvc
        .perform(authedGet("/api/tasks", other))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mockMvc
        .perform(authedGet("/api/notifications", other))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 수동_업무를_만들고_목록에서_보고_수정하고_지운다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "김고객");
    String body =
        "{\"title\":\"계약서 준비\",\"assigneeMembershipId\":"
            + agent.membership().getId()
            + ",\"dueDate\":\""
            + today()
            + "\",\"dueTime\":\"10:30\",\"relatedCustomerId\":"
            + customer
            + ",\"memo\":\"서류 확인\"}";

    JsonNode created =
        AuthFlowSupport.json(
            mockMvc
                .perform(jsonPostAs("/api/tasks", body, cookie))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.sourceType").value("MANUAL"))
                .andExpect(jsonPath("$.sourceId").isEmpty())
                .andExpect(jsonPath("$.dueTime").value("10:30"))
                .andExpect(jsonPath("$.relatedCustomerId").value(customer))
                .andExpect(jsonPath("$.relatedPropertyId").isEmpty())
                .andExpect(jsonPath("$.completedAt").isEmpty())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn());
    long taskId = created.get("taskId").longValue();

    mockMvc
        .perform(authedGet("/api/tasks?status=TODO", cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].taskId").value(taskId))
        .andExpect(jsonPath("$.items[0].assigneeName").value(agent.user().getName()))
        .andExpect(jsonPath("$.items[0].relatedCustomerName").value("김고객"))
        .andExpect(jsonPath("$.items[0].relatedPropertyName").isEmpty())
        .andExpect(jsonPath("$.items[0].memo").value("서류 확인"))
        .andExpect(jsonPath("$.items[0].version").value(1));
    mockMvc
        .perform(authedGet("/api/tasks?assigneeMembershipId=" + owner.membership().getId(), cookie))
        .andExpect(jsonPath("$.totalElements").value(0));

    mockMvc
        .perform(jsonPatchAs("/api/tasks/" + taskId, "{\"status\":\"DONE\"}", cookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.completedAt").isNotEmpty())
        .andExpect(jsonPath("$.version").value(2));
    assertError(
        mockMvc.perform(jsonPatchAs("/api/tasks/" + taskId, "{\"memo\":\"늦은 수정\"}", cookie, 1)),
        ErrorCode.VERSION_CONFLICT);
    assertFieldError(
        mockMvc.perform(jsonPatchAs("/api/tasks/" + taskId, "{\"memo\":\"헤더 없음\"}", cookie, null)),
        "If-Match",
        "REQUIRED");
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/tasks/" + taskId,
                "{\"status\":\"CANCELLED\",\"title\":\"계약서 준비 취소\",\"dueTime\":null}",
                cookie,
                2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.title").value("계약서 준비 취소"))
        .andExpect(jsonPath("$.dueTime").isEmpty())
        .andExpect(jsonPath("$.completedAt").isEmpty())
        .andExpect(jsonPath("$.version").value(3));

    mockMvc.perform(deleteAs("/api/tasks/" + taskId, cookie)).andExpect(status().isNoContent());
    assertError(
        mockMvc.perform(deleteAs("/api/tasks/" + taskId, cookie)), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 업무_입력이_잘못되면_400이다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff stranger = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long mine = owner.membership().getId();

    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/tasks",
                "{\"assigneeMembershipId\":" + mine + ",\"dueDate\":\"" + today() + "\"}",
                cookie)),
        "title",
        "REQUIRED");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/tasks", taskBody(stranger.membership().getId(), "업무", today()), cookie)),
        "assigneeMembershipId",
        "INVALID_VALUE");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/tasks",
                "{\"title\":\"업무\",\"assigneeMembershipId\":"
                    + mine
                    + ",\"dueDate\":\""
                    + today()
                    + "\",\"dueTime\":\"25:00\"}",
                cookie)),
        "dueTime",
        "INVALID_FORMAT");
    assertFieldError(
        mockMvc.perform(
            jsonPostAs(
                "/api/tasks",
                "{\"title\":\"업무\",\"assigneeMembershipId\":"
                    + mine
                    + ",\"dueDate\":\""
                    + today()
                    + "\",\"sourceType\":\"VISIT\"}",
                cookie)),
        "sourceType",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/tasks?status=OPEN", cookie)), "status", "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(authedGet("/api/notifications?size=20", cookie)), "size", "NOT_ALLOWED");
  }

  @Test
  void 중개보조원은_본인_담당_업무만_만들고_보고_고친다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff deputy = staffIn(owner.office(), OfficeMembershipRole.DEPUTY);
    Cookie ownerCookie = cookieOf(owner);
    Cookie deputyCookie = cookieOf(deputy);
    long ownerTask =
        createTask(ownerCookie, owner.membership().getId(), "대표 업무", today())
            .get("taskId")
            .longValue();
    long deputyTask =
        createTask(deputyCookie, deputy.membership().getId(), "보조 업무", today())
            .get("taskId")
            .longValue();

    assertError(
        mockMvc.perform(
            jsonPostAs(
                "/api/tasks", taskBody(owner.membership().getId(), "남 업무", today()), deputyCookie)),
        ErrorCode.FORBIDDEN);
    mockMvc
        .perform(authedGet("/api/tasks", deputyCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].taskId").value(deputyTask));
    assertError(
        mockMvc.perform(
            authedGet(
                "/api/tasks?assigneeMembershipId=" + owner.membership().getId(), deputyCookie)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/tasks/" + ownerTask, "{\"memo\":\"수정\"}", deputyCookie, 1)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(
            jsonPatchAs(
                "/api/tasks/" + deputyTask,
                "{\"assigneeMembershipId\":" + owner.membership().getId() + "}",
                deputyCookie,
                1)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(deleteAs("/api/tasks/" + ownerTask, deputyCookie)), ErrorCode.FORBIDDEN);
    mockMvc
        .perform(jsonPatchAs("/api/tasks/" + deputyTask, "{\"status\":\"DONE\"}", deputyCookie, 1))
        .andExpect(status().isOk());
    mockMvc
        .perform(deleteAs("/api/tasks/" + deputyTask, deputyCookie))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet("/api/tasks", ownerCookie))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void 방문이_확정되면_자동_업무가_생기고_완료_처리와_메모만_바꿀_수_있다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    long taskId = visitTaskOf(item);
    Cookie cookie = s.cookie();

    assertThat(taskColumn("assignee_membership_id", taskId))
        .isEqualTo(String.valueOf(s.base().owner().membership().getId()));
    assertThat(taskColumn("due_date", taskId)).isEqualTo(visitDay().toString());
    assertThat(taskColumn("related_customer_id", taskId))
        .isEqualTo(String.valueOf(s.base().customer()));
    assertThat(taskColumn("related_property_id", taskId))
        .isEqualTo(String.valueOf(s.base().propertyId()));
    assertThat(taskColumn("title", taskId)).startsWith("방문 안내 · ");

    mockMvc
        .perform(authedGet("/api/tasks", cookie))
        .andExpect(jsonPath("$.items[0].sourceType").value("VISIT"))
        .andExpect(jsonPath("$.items[0].dueTime").value("10:00"));
    assertError(
        mockMvc.perform(deleteAs("/api/tasks/" + taskId, cookie)), ErrorCode.AUTO_TASK_IMMUTABLE);
    assertError(
        mockMvc.perform(jsonPatchAs("/api/tasks/" + taskId, "{\"title\":\"바꿈\"}", cookie, 1)),
        ErrorCode.AUTO_TASK_IMMUTABLE);
    assertError(
        mockMvc.perform(
            jsonPatchAs("/api/tasks/" + taskId, "{\"status\":\"CANCELLED\"}", cookie, 1)),
        ErrorCode.AUTO_TASK_IMMUTABLE);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/tasks/" + taskId, "{\"status\":\"DONE\",\"memo\":\"안내 끝\"}", cookie, 1))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DONE"))
        .andExpect(jsonPath("$.memo").value("안내 끝"));
    mockMvc
        .perform(jsonPatchAs("/api/tasks/" + taskId, "{\"status\":\"TODO\"}", cookie, 2))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("TODO"))
        .andExpect(jsonPath("$.completedAt").isEmpty());
    assertThat(itemResult(item)).isEqualTo("SCHEDULED");

    cancel(cookie, s.coordinationId(), "{\"reason\":\"고객 사정\"}").andExpect(status().isOk());
    assertThat(taskColumn("status", taskId)).isEqualTo("CANCELLED");
  }

  @Test
  void 조율_담당자를_바꾸면_업무_담당자가_바뀌고_새_담당자에게_알림이_간다() throws Exception {
    SelectionFixture s = confirmed();
    TestData.Staff agent = staffIn(s.base().owner().office(), OfficeMembershipRole.AGENT);
    long item = scheduledItem(s.coordinationId());
    long taskId = visitTaskOf(item);

    mockMvc
        .perform(
            jsonPatchAs(
                "/api/visit-coordinations/" + s.coordinationId() + "/manager",
                "{\"managerMembershipId\":" + agent.membership().getId() + "}",
                s.cookie(),
                coordinationVersion(s.coordinationId())))
        .andExpect(status().isOk());

    assertThat(taskColumn("assignee_membership_id", taskId))
        .isEqualTo(String.valueOf(agent.membership().getId()));
    Cookie agentCookie = cookieOf(agent);
    mockMvc
        .perform(authedGet("/api/notifications", agentCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].type").value("ASSIGNEE_CHANGED"))
        .andExpect(jsonPath("$.items[0].targetType").value("VISIT_COORDINATION"))
        .andExpect(jsonPath("$.items[0].targetId").value(s.coordinationId()))
        .andExpect(jsonPath("$.items[0].read").value(false));
    assertThat(channelSender.delivered()).hasSize(1);
    assertThat(channelSender.delivered().get(0).recipientUserId()).isEqualTo(agent.user().getId());
  }

  @Test
  void 고객_담당자를_바꾸면_새_담당자에게_알림이_간다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long customer = createCustomer(cookie, "박고객", "010-7878-9090", owner.membership().getId());

    mockMvc
        .perform(
            jsonPatchAs(
                "/api/customers/" + customer,
                "{\"managingMembershipId\":" + agent.membership().getId() + "}",
                cookie,
                customerVersion(customer)))
        .andExpect(status().isOk());

    mockMvc
        .perform(authedGet("/api/notifications?unreadOnly=true", cookieOf(agent)))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].type").value("ASSIGNEE_CHANGED"))
        .andExpect(jsonPath("$.items[0].targetType").value("CUSTOMER"))
        .andExpect(jsonPath("$.items[0].targetId").value(customer));
    mockMvc
        .perform(authedGet("/api/notifications", cookie))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void 알림함은_본인_알림만_최근순으로_보이고_읽음_처리한다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    TestData.Staff agent = staffIn(owner.office(), OfficeMembershipRole.AGENT);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();
    long userId = owner.user().getId();
    long first = insertNotification(officeId, userId, "PAYMENT", "SUBSCRIPTION", null);
    long second = insertNotification(null, userId, "SECURITY_ALERT", "NONE", null);
    long otherOffice =
        insertNotification(
            newStaff(OfficeMembershipRole.OWNER).office().getId(), userId, "NOTICE", "NONE", null);
    long agents = insertNotification(officeId, agent.user().getId(), "NOTICE", "NONE", null);

    mockMvc
        .perform(get("/api/notifications").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].notificationId").value(second))
        .andExpect(jsonPath("$.items[0].targetId").isEmpty())
        .andExpect(jsonPath("$.items[1].notificationId").value(first))
        .andExpect(jsonPath("$.items[1].targetType").value("SUBSCRIPTION"));

    mockMvc
        .perform(jsonPostAs("/api/notifications/" + first + "/read", "", cookie))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPostAs("/api/notifications/" + first + "/read", "", cookie))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(authedGet("/api/notifications?unreadOnly=true", cookie))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].notificationId").value(second));
    mockMvc
        .perform(authedGet("/api/home", cookie))
        .andExpect(jsonPath("$.unreadNotificationCount").value(1))
        .andExpect(jsonPath("$.unreadVisitRequestNoticeCount").value(0))
        .andExpect(jsonPath("$.visitRequestSummary.pendingCount").value(0))
        .andExpect(jsonPath("$.visitRequestSummary.revalidationFailedCount").value(0))
        .andExpect(jsonPath("$.visitRequestSummary.timeClosedCount").value(0));
    assertError(
        mockMvc.perform(jsonPostAs("/api/notifications/" + agents + "/read", "", cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(jsonPostAs("/api/notifications/" + otherOffice + "/read", "", cookie)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 홈은_오늘_방문과_조율_상태별_건수와_미완료_업무와_캘린더를_돌려준다() throws Exception {
    SelectionFixture s = confirmed();
    long item = scheduledItem(s.coordinationId());
    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET scheduled_start_at = ?, scheduled_end_at = ? WHERE id = ?",
        java.sql.Timestamp.from(at(today(), "12:00")),
        java.sql.Timestamp.from(at(today(), "12:30")),
        item);
    long ownerMembership = s.base().owner().membership().getId();
    long overdue =
        createTask(s.cookie(), ownerMembership, "지난 업무", today().minusDays(1))
            .get("taskId")
            .longValue();

    mockMvc
        .perform(authedGet("/api/home", s.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.todayScheduledVisits.length()").value(1))
        .andExpect(jsonPath("$.todayScheduledVisits[0].scheduleItemId").value(item))
        .andExpect(jsonPath("$.todayScheduledVisits[0].coordinationId").value(s.coordinationId()))
        .andExpect(jsonPath("$.todayScheduledVisits[0].customerName").value("이수요"))
        .andExpect(jsonPath("$.todayScheduledVisits[0].propertyAddress").isNotEmpty())
        .andExpect(jsonPath("$.activeCoordinationCount").value(1))
        .andExpect(jsonPath("$.coordinationStatusCounts.length()").value(9))
        .andExpect(jsonPath("$.coordinationStatusCounts[4].status").value("CONFIRMED"))
        .andExpect(jsonPath("$.coordinationStatusCounts[4].count").value(1))
        .andExpect(jsonPath("$.coordinationStatusCounts[0].count").value(0))
        .andExpect(jsonPath("$.openTasks.length()").value(1))
        .andExpect(jsonPath("$.openTasks[0].taskId").value(overdue))
        .andExpect(jsonPath("$.openTasks[0].overdue").value(true))
        .andExpect(jsonPath("$.openTasks[0].assigneeMembershipId").value(ownerMembership))
        .andExpect(jsonPath("$.openFollowupTasks.length()").value(0))
        .andExpect(jsonPath("$.leaseExpiringWithin90DaysCount").value(0))
        .andExpect(jsonPath("$.calendarDays[?(@.date == '" + today() + "')].visitCount").value(1));

    Cookie deputy = cookieOf(staffIn(s.base().owner().office(), OfficeMembershipRole.DEPUTY));
    mockMvc
        .perform(authedGet("/api/home", deputy))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.todayScheduledVisits.length()").value(0))
        .andExpect(jsonPath("$.activeCoordinationCount").value(0))
        .andExpect(jsonPath("$.coordinationStatusCounts[4].count").value(0))
        .andExpect(jsonPath("$.openTasks.length()").value(0))
        .andExpect(jsonPath("$.calendarDays.length()").value(0))
        .andExpect(jsonPath("$.leaseExpiringWithin90DaysCount").value(0));
  }
}
