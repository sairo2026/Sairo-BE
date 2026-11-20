package com.sairo.be.global.security.access;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@IntegrationTest
class OfficeWriteRestrictionIntegrationTest extends VisitOutcomeTestBase {

  /**
   * 업무 데이터를 쓰는 컨트롤러는 가드 대상이어야 한다. 아래 컨트롤러는 계정·결제·알림·문의·내보내기처럼 제한 중에도 써야 하거나,
   * 사무소 구성원이 아닌 주체가 호출하거나, 서비스 계층에서 따로 판정하는 예외다. 운영자 콘솔(Ops로 시작하는 컨트롤러와 ops 패키지)은 전부 예외다.
   * 새 컨트롤러는 어느 쪽인지 여기에서 정해야 이 시험이 통과한다.
   */
  private static final Set<String> EXEMPT_CONTROLLERS =
      Set.of(
          "AccountSettingsController",
          "ApplyGrantController",
          "ApproverAccountController",
          "ApproverPortalApprovalController",
          "ApproverPortalPrivacyIntakeController",
          "ArchiveGrantController",
          "AuthSessionController",
          "BillingController",
          "EnterpriseLeadController",
          "ExportController",
          "GrantAuthController",
          "ImportSupportRequestController",
          "KakaoWebhookController",
          "LoginMethodController",
          "MembershipController",
          "NotificationController",
          "OfficeCloseController",
          "OfficeController",
          "OfficeMemberController",
          "OfficeRegistrationController",
          "PasswordStepUpController",
          "PrivacyRequestController",
          "PublicApproverConditionController",
          "PublicApproverRequestController",
          "PublicApproverReviewController",
          "PublicCustomerVisitLinkController",
          "PublicPrivacyIntakeController",
          "ReturnGrantController",
          "ReturnGrantVisitController",
          "SupportInquiryController",
          "TermsController",
          "VisitAssistantNoticeController",
          "VisitRequestNoticeController");

  private static final Map<String, String> PREVIOUSLY_UNGUARDED =
      Map.of(
          "/api/owner-occupants/1", "{}",
          "/api/customer-property-interests/1", "{}",
          "/api/demand-conditions/1", "{}",
          "/api/customer-consultations/1", "{}");

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping handlerMapping;

  private static String taskBody(long assignee) {
    return "{\"title\":\"제한 시험\",\"assigneeMembershipId\":"
        + assignee
        + ",\"dueDate\":\""
        + java.time.LocalDate.now()
        + "\"}";
  }

  private void setSubscription(long officeId, String status) {
    jdbcTemplate.update("DELETE FROM subscription WHERE office_id = ?", officeId);
    if ("TRIAL".equals(status)) {
      long event =
          new BillingFixtures(jdbcTemplate)
              .trialEvent(
                  "GENERAL",
                  "DURATION",
                  Instant.now().minus(5, ChronoUnit.DAYS),
                  Instant.now().plus(30, ChronoUnit.DAYS),
                  null,
                  "ACTIVE");
      jdbcTemplate.update(
          "INSERT INTO subscription (office_id, plan, status, trial_event_id, trial_started_at,"
              + " trial_ends_at) VALUES (?, 'SOLO', 'TRIAL', ?, now(), now() + interval '60 days')",
          officeId,
          event);
      return;
    }
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
  void 고객_관계와_상담_정정_경로도_제한_상태에서_거부한다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();

    for (String restricted : new String[] {"PAST_DUE_RESTRICTED", "SUSPENDED", "ENDED"}) {
      setSubscription(officeId, restricted);
      for (Map.Entry<String, String> sample : PREVIOUSLY_UNGUARDED.entrySet()) {
        assertError(
            mockMvc.perform(jsonPatchAs(sample.getKey(), sample.getValue(), cookie, 1)),
            ErrorCode.OFFICE_WRITE_RESTRICTED);
      }
    }
  }

  @Test
  void 고객_관계와_상담_정정_경로는_허용_상태에서_제한_오류로_막히지_않는다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long officeId = owner.office().getId();

    for (String writable : new String[] {"PRE_PAYMENT", "TRIAL", "ACTIVE", "PAST_DUE_GRACE"}) {
      setSubscription(officeId, writable);
      for (Map.Entry<String, String> sample : PREVIOUSLY_UNGUARDED.entrySet()) {
        mockMvc
            .perform(jsonPatchAs(sample.getKey(), sample.getValue(), cookie, 1))
            .andExpect(
                result ->
                    assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(ErrorCode.OFFICE_WRITE_RESTRICTED.name()));
      }
    }
  }

  @Test
  void 변경성_엔드포인트는_모두_가드_대상이거나_예외_컨트롤러로_분류되어야_한다() {
    Set<RequestMethod> mutating =
        Set.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
    List<String> unclassified = new ArrayList<>();
    Set<String> guardedControllers = new TreeSet<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
        handlerMapping.getHandlerMethods().entrySet()) {
      Class<?> controller = entry.getValue().getBeanType();
      String name = controller.getSimpleName();
      if (!controller.getPackageName().startsWith("com.sairo.be.domain")
          || entry.getKey().getPathPatternsCondition() == null) {
        continue;
      }
      boolean exempt =
          EXEMPT_CONTROLLERS.contains(name)
              || name.startsWith("Ops")
              || controller.getPackageName().contains(".domain.ops.");
      for (RequestMethod method : entry.getKey().getMethodsCondition().getMethods()) {
        if (!mutating.contains(method)) {
          continue;
        }
        for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
          String sample = pattern.replaceAll("\\{[^}]*}", "1");
          if (OfficeWriteRestrictionInterceptor.isRestrictedPath(method.name(), sample)) {
            guardedControllers.add(name);
          } else if (!exempt) {
            unclassified.add(method + " " + pattern + " [" + name + "]");
          }
        }
      }
    }
    assertThat(unclassified).as("가드 대상에도 예외에도 없는 변경성 엔드포인트").isEmpty();
    assertThat(guardedControllers)
        .contains("CustomerRelationController", "CustomerConsultationController");
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
