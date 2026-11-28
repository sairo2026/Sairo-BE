package com.sairo.be.domain.visit.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.visit.repository.ScheduleItemRepository;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class PublicConfirmedScheduleIntegrationTest extends VisitOutcomeTestBase {

  private static final Pattern CODE = Pattern.compile("([0-9]{6})");

  @MockitoSpyBean private ScheduleItemRepository scheduleItems;

  private ResultActions customerConfirmed(String token) throws Exception {
    return mockMvc.perform(get("/api/public/customer-visit-links/" + token + "/confirmed"));
  }

  private ResultActions approverConfirmed(String token) throws Exception {
    return mockMvc.perform(
        get("/api/public/approver-property-links/" + token + "/confirmed-schedules"));
  }

  @Test
  void 확정_일정_조회_도중_취소가_완료되어도_응답은_한_시점의_상태와_버전을_사용한다() throws Exception {
    RequestFixture f = requestFixture();
    String customerToken = reissueCustomerToken(f.cookie(), f.coordinationId());
    long requestId = submit(f);
    selectNow(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()), requestId)
        .andExpect(status().isOk());

    CountDownLatch rowsRead = new CountDownLatch(1);
    CountDownLatch finishRead = new CountDownLatch(1);
    AtomicReference<Thread> reader = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Object rows = invocation.callRealMethod();
              if (Thread.currentThread() == reader.get()) {
                rowsRead.countDown();
                if (!finishRead.await(30, TimeUnit.SECONDS)) {
                  throw new AssertionError("Timed out waiting for the concurrent cancellation");
                }
              }
              return rows;
            })
        .when(scheduleItems)
        .listByCoordination(f.coordinationId());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<String> pending =
          executor.submit(
              () -> {
                reader.set(Thread.currentThread());
                return customerConfirmed(customerToken)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
              });
      assertThat(rowsRead.await(30, TimeUnit.SECONDS)).isTrue();
      cancel(f.cookie(), f.coordinationId(), "{}").andExpect(status().isOk());
      finishRead.countDown();
      assertThat(pending.get(30, TimeUnit.SECONDS))
          .contains("\"state\":\"VALID\"", "\"scheduleVersion\":1");
      customerConfirmed(customerToken)
          .andExpect(jsonPath("$.state").value("CANCELLED"))
          .andExpect(jsonPath("$.scheduleVersion").value(greaterThan(1)));
    } finally {
      finishRead.countDown();
      executor.shutdownNow();
      reset(scheduleItems);
    }
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
  void 이메일_인증_포털은_본인_확정_일정만_보여주고_24시간_제한을_유지한다() throws Exception {
    RequestFixture f = requestFixture();
    jdbcTemplate.update(
        "UPDATE customer SET email = ? WHERE id = ?",
        "confirmed-portal@example.test",
        f.customer());
    String token = reissueCustomerToken(f.cookie(), f.coordinationId());
    mockMvc
        .perform(
            com.sairo.be.support.AuthFlowSupport.withCsrf(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/public/customer-visit-links/" + token + "/portal/otp")))
        .andExpect(status().isOk());
    String code =
        mailSender.sent().stream()
            .filter(message -> "confirmed-portal@example.test".equals(message.to()))
            .map(message -> CODE.matcher(message.body()))
            .filter(java.util.regex.Matcher::find)
            .reduce((a, b) -> b)
            .orElseThrow()
            .group(1);
    Cookie session =
        mockMvc
            .perform(
                jsonPostAs(
                    "/api/public/customer-visit-links/" + token + "/portal/verify",
                    "{\"code\":\"" + code + "\"}",
                    null))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("SAIRO_CUSTOMER_PORTAL");
    assertThat(session).isNotNull();
    long requestId = submit(f);
    selectNow(issueApproverToken(f.cookie(), f.propertyId(), f.approvalId()), requestId)
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/public/customer-visit-links/portal").cookie(session))
        .andExpect(jsonPath("$.items[0].canViewSchedule").value(true));
    String url =
        "/api/public/customer-visit-links/portal/requests/" + f.coordinationId() + "/confirmed";
    assertError(mockMvc.perform(get(url)), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    mockMvc
        .perform(get(url).cookie(session))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.state").value("VALID"))
        .andExpect(jsonPath("$.items.length()").value(1));
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = CURRENT_DATE - 1 WHERE coordination_id = ?",
        f.coordinationId());
    assertThat(
            scheduleItems.viewableCustomerCoordinationIds(
                List.of(f.coordinationId()),
                Instant.now().minus(Duration.ofHours(24)),
                LocalDate.now(ZoneId.of("Asia/Seoul"))))
        .isEmpty();
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = CURRENT_DATE + 30 WHERE coordination_id = ?",
        f.coordinationId());
    assertError(
        mockMvc.perform(
            get(url.replace(String.valueOf(f.coordinationId()), "999999999")).cookie(session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    RequestFixture anotherCustomer = requestFixture(f.owner(), true, true);
    RequestFixture anotherOffice = requestFixture();
    assertError(
        mockMvc.perform(
            get("/api/public/customer-visit-links/portal/requests/"
                    + anotherCustomer.coordinationId()
                    + "/confirmed")
                .cookie(session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    assertError(
        mockMvc.perform(
            get("/api/public/customer-visit-links/portal/requests/"
                    + anotherOffice.coordinationId()
                    + "/confirmed")
                .cookie(session)),
        ErrorCode.PUBLIC_LINK_NOT_FOUND);
    jdbcTemplate.update(
        "UPDATE visit_schedule_item SET scheduled_start_at = now() - interval '3 days',"
            + " scheduled_end_at = now() - interval '3 days' + interval '30 minutes' WHERE"
            + " coordination_id = ?",
        f.coordinationId());
    assertError(mockMvc.perform(get(url).cookie(session)), ErrorCode.PUBLIC_LINK_EXPIRED);
    mockMvc
        .perform(get("/api/public/customer-visit-links/portal").cookie(session))
        .andExpect(jsonPath("$.items[0].canViewSchedule").value(false));
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = CURRENT_DATE - 1 WHERE coordination_id = ?",
        f.coordinationId());
    assertError(mockMvc.perform(get(url).cookie(session)), ErrorCode.PUBLIC_LINK_NOT_FOUND);
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = CURRENT_DATE + 30, revoked_at = now()"
            + " WHERE coordination_id = ?",
        f.coordinationId());
    assertError(mockMvc.perform(get(url).cookie(session)), ErrorCode.PUBLIC_LINK_NOT_FOUND);
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
