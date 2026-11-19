package com.sairo.be.global.security.access;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

@IntegrationTest
class OfficeWriteRestrictionIntegrationTest extends VisitOutcomeTestBase {

  private static String taskBody(long assignee) {
    return "{\"title\":\"제한 시험\",\"assigneeMembershipId\":"
        + assignee
        + ",\"dueDate\":\""
        + java.time.LocalDate.now()
        + "\"}";
  }

  private void setSubscription(long officeId, String status) {
    jdbcTemplate.update("DELETE FROM subscription WHERE office_id = ?", officeId);
    jdbcTemplate.update(
        "INSERT INTO subscription (office_id, plan, status) VALUES (?, 'SOLO', ?)",
        officeId,
        status);
  }

  @Test
  void 읽기_전용_사무소는_업무_쓰기를_서버가_거부하고_읽기와_알림은_허용한다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();
    String body = taskBody(owner.membership().getId());

    for (String restricted : new String[] {"PAST_DUE_RESTRICTED", "SUSPENDED", "ENDED"}) {
      setSubscription(officeId, restricted);
      assertError(
          mockMvc.perform(jsonPostAs("/api/tasks", body, cookie)),
          ErrorCode.OFFICE_WRITE_RESTRICTED);
      assertError(
          mockMvc.perform(jsonPostAs("/api/customers", "{}", cookie)),
          ErrorCode.OFFICE_WRITE_RESTRICTED);
      mockMvc.perform(authedGet("/api/tasks", cookie)).andExpect(status().isOk());
      mockMvc
          .perform(jsonPostAs("/api/notifications/read-all", "", cookie))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void 제한이_풀리면_같은_세션으로_다시_쓸_수_있다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();
    String body = taskBody(owner.membership().getId());

    setSubscription(officeId, "PAST_DUE_RESTRICTED");
    assertError(
        mockMvc.perform(jsonPostAs("/api/tasks", body, cookie)), ErrorCode.OFFICE_WRITE_RESTRICTED);

    setSubscription(officeId, "ACTIVE");
    mockMvc
        .perform(jsonPostAs("/api/tasks", body, cookie))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.taskId").isNumber());
  }

  @Test
  void 상태별로_쓰기는_허용_상태만_성공하고_복구_경로는_제한_상태에서도_성공한다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();
    String body = taskBody(owner.membership().getId());

    for (String writable : new String[] {"PRE_PAYMENT", "ACTIVE", "PAST_DUE_GRACE"}) {
      setSubscription(officeId, writable);
      mockMvc.perform(jsonPostAs("/api/tasks", body, cookie)).andExpect(status().isCreated());
    }
    for (String restricted : new String[] {"PAST_DUE_RESTRICTED", "SUSPENDED", "ENDED"}) {
      setSubscription(officeId, restricted);
      assertError(
          mockMvc.perform(jsonPostAs("/api/tasks", body, cookie)),
          ErrorCode.OFFICE_WRITE_RESTRICTED);
      mockMvc.perform(authedGet("/api/subscriptions/current", cookie)).andExpect(status().isOk());
      mockMvc
          .perform(jsonPostAs("/api/notifications/read-all", "", cookie))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void 제한된_경로만_검사한다() {
    assertThat(OfficeWriteRestrictionInterceptor.isRestrictedPath("POST", "/api/tasks")).isTrue();
    assertThat(OfficeWriteRestrictionInterceptor.isRestrictedPath("PATCH", "/api/properties/3"))
        .isTrue();
    assertThat(OfficeWriteRestrictionInterceptor.isRestrictedPath("GET", "/api/tasks")).isFalse();
    assertThat(OfficeWriteRestrictionInterceptor.isRestrictedPath("POST", "/api/exports"))
        .isFalse();
    assertThat(OfficeWriteRestrictionInterceptor.isRestrictedPath("POST", "/api/support-inquiries"))
        .isFalse();
    assertThat(
            OfficeWriteRestrictionInterceptor.isRestrictedPath(
                "POST", "/api/offices/current/subscription/cancel"))
        .isFalse();
  }
}
