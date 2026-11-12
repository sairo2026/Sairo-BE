package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.StubIntegrationProbe;
import com.sairo.be.global.port.stub.StubPaymentGateway;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OpsSystemIntegrationTest extends OpsConsoleTestBase {

  private static final String JOBS = "/api/ops/system-jobs";
  private static final String INCIDENTS = "/api/ops/incidents";
  private static final String INTEGRATIONS = "/api/ops/integrations";
  private static final String PRIVACY = "/api/ops/privacy-incidents";

  @Autowired private StubIntegrationProbe probe;
  @Autowired private StubPaymentGateway gateway;

  @Autowired
  @Qualifier("billingKeyCipher") private TextCipher cipher;

  private Operator ops;
  private Cookie opsSession;
  private Operator billing;
  private Cookie billingSession;
  private Operator privacy;
  private Cookie privacySession;

  @BeforeEach
  void setUp() {
    ops = operator("최운영", OperatorRole.OPS);
    opsSession = sessionOf(ops);
    billing = operator("박결제", OperatorRole.BILLING);
    billingSession = sessionOf(billing);
    privacy = operator("정개인", OperatorRole.PRIVACY);
    privacySession = sessionOf(privacy);
    probe.clear();
    gateway.reset();
  }

  @AfterEach
  void tearDown() {
    probe.clear();
  }

  private static String at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(9)).toString();
  }

  private ResultActions patch(String path, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(cookie == null ? jsonPatch(path, body) : jsonPatch(path, body, cookie));
  }

  private static long idOf(ResultActions result, String field) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    return Long.parseLong(body.replaceAll("(?s).*\"" + field + "\":(\\d+).*", "$1"));
  }

  private String incidentBody(String services, String startedAt) {
    return "{\"title\":\"PG 연동 지연\",\"affectedServices\":"
        + services
        + ",\"affectedOfficeIds\":[],\"startedAt\":\""
        + startedAt
        + "\",\"rootCause\":null}";
  }

  private long createIncident() throws Exception {
    return idOf(
        post(
                INCIDENTS,
                incidentBody("[\"BILLING\"]", at(Instant.now().minusSeconds(600))),
                opsSession)
            .andExpect(status().isCreated()),
        "incidentId");
  }

  private long insertIntegration(String environment, String provider, String functionCode) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO integration_config (environment, provider, function_code,"
            + " secret_reference_id, masked_connection, retention_policy, contract_status,"
            + " consent_status) VALUES (?, ?, ?, 'secret-ref-example', 'ac***01', '계약 종료 뒤 파기',"
            + " 'PENDING', 'PENDING') RETURNING id",
        Long.class,
        environment,
        provider,
        functionCode);
  }

  private long insertFailedRun(String jobName, String key) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO system_job_run (job_name, idempotency_key, status, finished_at, error_code)"
            + " VALUES (?, ?, 'FAILED', now(), 'TimeoutException') RETURNING id",
        Long.class,
        jobName,
        key);
  }

  private ResultActions rerun(String jobName, long runId, Cookie cookie) throws Exception {
    return post(JOBS + "/" + jobName + "/rerun", "{\"runId\":" + runId + "}", cookie);
  }

  private void publishCompanyInfo() {
    jdbcTemplate.update(
        "INSERT INTO company_service_info (version_no, business_name, representative_name,"
            + " business_number, address, contact_phone, support_email, support_hours,"
            + " emergency_contact, privacy_officer, privacy_contact, privacy_request_path,"
            + " cancellation_path, inquiry_delivery_tested_at, reviewed_by_operator_id,"
            + " published_at) VALUES (1, '상호', '대표자', '000-00-00000', '사업장 주소',"
            + " '02-0000-0000', 'support@example.com', '평일', '02-0000-0001', '개인정보 담당',"
            + " 'privacy@example.com', '설정', '설정 > 구독', now(), ?, now())",
        ops.id());
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_맞지_않으면_403이며_없는_자원은_404다() throws Exception {
    Cookie support = sessionOf(operator("김지원", OperatorRole.SUPPORT));
    for (String path : new String[] {JOBS, INCIDENTS, INTEGRATIONS, PRIVACY}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(support)), ErrorCode.FORBIDDEN);
    }
    assertError(mockMvc.perform(get(INCIDENTS).cookie(billingSession)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get(INTEGRATIONS).cookie(billingSession)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get(PRIVACY).cookie(opsSession)), ErrorCode.FORBIDDEN);
    mockMvc.perform(get(INTEGRATIONS).cookie(privacySession)).andExpect(status().isOk());
    mockMvc.perform(get(JOBS).cookie(billingSession)).andExpect(status().isOk());
    for (String path :
        new String[] {
          JOBS + "/billing-retry/rerun", INCIDENTS, INTEGRATIONS + "/1/check", PRIVACY
        }) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, "{}", support), ErrorCode.FORBIDDEN);
    }
    for (String path : new String[] {INCIDENTS + "/1", INTEGRATIONS + "/1", PRIVACY + "/1"}) {
      assertError(patch(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(patch(path, "{}", support), ErrorCode.FORBIDDEN);
    }
    assertError(post(INTEGRATIONS + "/1/check", "", privacySession), ErrorCode.FORBIDDEN);
    assertError(
        patch(INTEGRATIONS + "/1", "{\"enabled\":false}", privacySession), ErrorCode.FORBIDDEN);
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    Cookie member = memberSession(owner);
    for (String path : new String[] {JOBS, INCIDENTS, INTEGRATIONS, PRIVACY}) {
      assertError(mockMvc.perform(get(path).cookie(member)), ErrorCode.UNAUTHENTICATED);
    }
    assertError(
        patch(INCIDENTS + "/999999", "{\"status\":\"CONFIRMED\"}", opsSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        patch(INTEGRATIONS + "/999999", "{\"enabled\":false}", opsSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(post(INTEGRATIONS + "/999999/check", "", opsSession), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        patch(PRIVACY + "/999999", "{\"responseEvidence\":\"보관함 #1\"}", privacySession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(
            PRIVACY,
            "{\"incidentLogId\":999999,\"affectedScope\":\"범위\",\"reportRequirement\":\"해당 없음\","
                + "\"noticeRequirement\":\"해당 없음\"}",
            privacySession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        rerun("ops-announcement-publish", 999999, opsSession), ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(get(JOBS).param("status", "DONE").cookie(opsSession)),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(get(JOBS).param("jobName", "Billing Retry").cookie(opsSession)),
        "jobName",
        "NOT_ALLOWED");
  }

  @Test
  void 장애는_감지부터_종료까지_한_단계씩만_바꾸고_종료_시각을_서버가_기록한다() throws Exception {
    Instant now = Instant.now();
    assertFieldError(
        post(INCIDENTS, incidentBody("[]", at(now.minusSeconds(60))), opsSession),
        "affectedServices",
        "REQUIRED");
    assertFieldError(
        post(INCIDENTS, incidentBody("[\"billing\"]", at(now.minusSeconds(60))), opsSession),
        "affectedServices",
        "NOT_ALLOWED");
    assertFieldError(
        post(INCIDENTS, incidentBody("[\"API\"]", at(now.plusSeconds(3600))), opsSession),
        "startedAt",
        "OUT_OF_RANGE");
    assertError(
        post(
            INCIDENTS,
            incidentBody("[\"API\"]", at(now.minusSeconds(60)))
                .replace("\"affectedOfficeIds\":[]", "\"affectedOfficeIds\":[999999]"),
            opsSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    Operator disabled = operator("퇴사자", OperatorRole.OPS);
    jdbcTemplate.update(
        "UPDATE operator_account SET status = 'DISABLED' WHERE id = ?", disabled.id());
    assertFieldError(
        post(
            INCIDENTS,
            incidentBody("[\"API\"]", at(now.minusSeconds(60)))
                .replace("\"rootCause\":null", "\"assignedOperatorId\":" + disabled.id()),
            opsSession),
        "assignedOperatorId",
        "NOT_ALLOWED");

    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    long id =
        idOf(
            post(
                    INCIDENTS,
                    incidentBody("[\"BILLING\",\"API\",\"BILLING\"]", at(now.minusSeconds(600)))
                        .replace(
                            "\"affectedOfficeIds\":[]",
                            "\"affectedOfficeIds\":[" + owner.office().getId() + "]"),
                    opsSession)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DETECTED"))
                .andExpect(jsonPath("$.detectedAt").isNotEmpty()),
            "incidentId");
    mockMvc
        .perform(get(INCIDENTS).cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].affectedServices.length()").value(2))
        .andExpect(jsonPath("$.items[0].affectedOffices[0]").value(owner.office().getId()));

    assertError(
        patch(INCIDENTS + "/" + id, "{\"status\":\"RESPONDING\"}", opsSession),
        ErrorCode.INVALID_TRANSITION);
    assertFieldError(
        patch(INCIDENTS + "/" + id, "{\"status\":\"FIXED\"}", opsSession), "status", "NOT_ALLOWED");
    assertFieldError(
        patch(
            INCIDENTS + "/" + id,
            "{\"status\":\"CONFIRMED\",\"recoveredAt\":\"" + at(now) + "\"}",
            opsSession),
        "recoveredAt",
        "NOT_ALLOWED");
    patch(
            INCIDENTS + "/" + id,
            "{\"status\":\"DETECTED\",\"rootCause\":\"PG 타임아웃\",\"assignedOperatorId\":"
                + ops.id()
                + "}",
            opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DETECTED"))
        .andExpect(jsonPath("$.rootCause").value("PG 타임아웃"))
        .andExpect(jsonPath("$.assignedOperatorId").value(ops.id()));
    patch(INCIDENTS + "/" + id, "{\"status\":\"CONFIRMED\"}", opsSession)
        .andExpect(jsonPath("$.rootCause").value("PG 타임아웃"));
    patch(INCIDENTS + "/" + id, "{\"status\":\"RESPONDING\"}", opsSession)
        .andExpect(jsonPath("$.recoveredAt").value(Matchers.nullValue()));
    assertError(
        patch(INCIDENTS + "/" + id, "{\"status\":\"CONFIRMED\"}", opsSession),
        ErrorCode.INVALID_TRANSITION);
    patch(INCIDENTS + "/" + id, "{\"status\":\"RECOVERED\"}", opsSession)
        .andExpect(jsonPath("$.recoveredAt").isNotEmpty())
        .andExpect(jsonPath("$.closedAt").value(Matchers.nullValue()));
    patch(INCIDENTS + "/" + id, "{\"status\":\"CLOSED\",\"summary\":\"복구 완료\"}", opsSession)
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.closedAt").isNotEmpty())
        .andExpect(jsonPath("$.summary").value("복구 완료"));
    mockMvc
        .perform(get(INCIDENTS).param("status", "CLOSED").cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(1));
    mockMvc
        .perform(get(INCIDENTS).param("status", "DETECTED").cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertThat(auditCount("INCIDENT_CREATED")).isEqualTo(1);
    assertThat(auditCount("INCIDENT_UPDATED")).isEqualTo(5);
  }

  @Test
  void 개인정보_사고는_장애_한_건에_하나만_연결하고_담당자는_요청한_개인정보담당이다() throws Exception {
    long incident = createIncident();
    String body =
        "{\"incidentLogId\":"
            + incident
            + ",\"affectedScope\":\"가입 사무소 3곳의 고객 연락처\",\"reportRequirement\":\"해당 없음\","
            + "\"noticeRequirement\":\"정보주체 통지 필요\",\"noticeDueAt\":\""
            + at(Instant.now().plusSeconds(86_400))
            + "\"}";
    long id =
        idOf(
            post(PRIVACY, body, privacySession)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.incidentLogId").value(incident))
                .andExpect(jsonPath("$.assignedOperatorId").value(privacy.id()))
                .andExpect(jsonPath("$.reportDueAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.noticeDueAt").isNotEmpty()),
            "privacyIncidentId");
    assertError(post(PRIVACY, body, privacySession), ErrorCode.INVALID_TRANSITION);
    assertFieldError(
        post(PRIVACY, body.replace("\"해당 없음\"", "\"\""), privacySession),
        "reportRequirement",
        "REQUIRED");
    mockMvc
        .perform(get(PRIVACY).cookie(privacySession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].responseEvidence").value(Matchers.nullValue()));
    patch(PRIVACY + "/" + id, "{\"responseEvidence\":\"신고 접수증 보관함 #2026-09\"}", privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.responseEvidence").value("신고 접수증 보관함 #2026-09"));
    assertFieldError(
        patch(PRIVACY + "/" + id, "{\"responseEvidence\":\"<b>x</b>\"}", privacySession),
        "responseEvidence",
        "HTML_NOT_ALLOWED");
    mockMvc
        .perform(get(PRIVACY).cookie(privacySession))
        .andExpect(jsonPath("$.items[0].responseEvidence").value("신고 접수증 보관함 #2026-09"));
    assertThat(auditCount("PRIVACY_INCIDENT_CREATED")).isEqualTo(1);
    assertThat(auditCount("PRIVACY_INCIDENT_EVIDENCE_UPDATED")).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM ops_audit_log WHERE detail LIKE '%연락처%' OR detail LIKE"
                    + " '%보관함%'"))
        .isZero();
  }

  @Test
  void 운영_연동_활성화는_계약_동의_점검과_회사_정보_출시_준비가_끝나야_하고_승인자를_기록한다() throws Exception {
    long test = insertIntegration("TEST", "mail-provider-a", "MAIL");
    long prod = insertIntegration("PRODUCTION", "pg-provider", "PAYMENT");
    mockMvc
        .perform(get(INTEGRATIONS).param("environment", "PRODUCTION").cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].integrationId").value(prod))
        .andExpect(jsonPath("$.items[0].secretReferenceId").value("secret-ref-example"))
        .andExpect(jsonPath("$.items[0].maskedConnection").value("ac***01"))
        .andExpect(jsonPath("$.items[0].approvedByOperatorId").value(Matchers.nullValue()));
    assertFieldError(
        mockMvc.perform(get(INTEGRATIONS).param("environment", "STAGE").cookie(opsSession)),
        "environment",
        "NOT_ALLOWED");

    patch(
            INTEGRATIONS + "/" + test,
            "{\"enabled\":true,\"provider\":\"mail-provider-b\",\"processingRegion\":\"KR\"}",
            opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.provider").value("mail-provider-b"))
        .andExpect(jsonPath("$.contractNoticeReviewedAt").value(Matchers.nullValue()));
    assertFieldError(
        patch(
            INTEGRATIONS + "/" + test,
            "{\"enabled\":true,\"contractStatus\":\"DONE\"}",
            opsSession),
        "contractStatus",
        "NOT_ALLOWED");
    assertFieldError(patch(INTEGRATIONS + "/" + test, "{}", opsSession), "enabled", "REQUIRED");

    assertError(
            patch(INTEGRATIONS + "/" + prod, "{\"enabled\":true}", opsSession),
            ErrorCode.INTEGRATION_PREREQUISITE_INCOMPLETE)
        .andExpect(
            jsonPath("$.missingItems")
                .value(
                    Matchers.hasItems(
                        "CONTRACT_SIGNED",
                        "CONSENT",
                        "CONTRACT_NOTICE_REVIEW",
                        "COMPANY_INFO_PUBLISHED_VERSION")));
    String ready =
        "{\"enabled\":true,\"contractStatus\":\"SIGNED\",\"consentStatus\":\"GRANTED\","
            + "\"contractNoticeReviewed\":true}";
    assertError(
            patch(INTEGRATIONS + "/" + prod, ready, opsSession),
            ErrorCode.INTEGRATION_PREREQUISITE_INCOMPLETE)
        .andExpect(
            jsonPath("$.missingItems")
                .value(Matchers.everyItem(Matchers.startsWith("COMPANY_INFO_"))));
    publishCompanyInfo();
    Operator revoked = operator("회수됨", OperatorRole.OPS);
    Cookie revokedSession = sessionOf(revoked);
    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", revoked.id());
    assertError(patch(INTEGRATIONS + "/" + prod, ready, revokedSession), ErrorCode.FORBIDDEN);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT enabled FROM integration_config WHERE id = ?", Boolean.class, prod))
        .isFalse();

    patch(INTEGRATIONS + "/" + prod, ready, opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.contractStatus").value("SIGNED"))
        .andExpect(jsonPath("$.contractNoticeReviewedAt").isNotEmpty());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT approved_by_operator_id FROM integration_config WHERE id = ?",
                Long.class,
                prod))
        .isEqualTo(ops.id());

    assertError(
            patch(
                INTEGRATIONS + "/" + prod, "{\"enabled\":true,\"provider\":\"pg-b\"}", opsSession),
            ErrorCode.INTEGRATION_PREREQUISITE_INCOMPLETE)
        .andExpect(jsonPath("$.missingItems[0]").value("CONTRACT_NOTICE_REVIEW"));
    assertError(
        patch(
            INTEGRATIONS + "/" + prod,
            "{\"enabled\":true,\"contractStatus\":\"EXPIRED\"}",
            opsSession),
        ErrorCode.INTEGRATION_PREREQUISITE_INCOMPLETE);
    patch(
            INTEGRATIONS + "/" + prod,
            "{\"enabled\":false,\"contractStatus\":\"EXPIRED\"}",
            opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
    assertThat(auditCount("INTEGRATION_UPDATED")).isEqualTo(3);
  }

  @Test
  void 연결_검사는_실패해도_200이고_실패한_검사는_운영담당만_다시_실행한다() throws Exception {
    long id = insertIntegration("TEST", "storage-provider", "STORAGE");
    post(INTEGRATIONS + "/" + id + "/check", "", opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastCheckResult").value("SUCCESS"))
        .andExpect(jsonPath("$.lastSuccessAt").isNotEmpty());
    probe.failWith(id, "PROVIDER_TIMEOUT");
    post(INTEGRATIONS + "/" + id + "/check", "", opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastCheckResult").value("FAILED"))
        .andExpect(jsonPath("$.lastSuccessAt").isNotEmpty());
    long failedRun =
        jdbcTemplate.queryForObject(
            "SELECT id FROM system_job_run WHERE job_name = 'ops-integration-check' AND status ="
                + " 'FAILED'",
            Long.class);
    mockMvc
        .perform(
            get(JOBS)
                .param("jobName", "ops-integration-check")
                .param("status", "FAILED")
                .cookie(opsSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].runId").value(failedRun))
        .andExpect(jsonPath("$.items[0].jobType").value("INTEGRATION_CHECK"))
        .andExpect(jsonPath("$.items[0].rerunRoles[0]").value("OPS"))
        .andExpect(jsonPath("$.items[0].errorCode").value("PROVIDER_TIMEOUT"))
        .andExpect(jsonPath("$.items[0].attemptCount").value(1));

    assertError(rerun("ops-integration-check", failedRun, billingSession), ErrorCode.FORBIDDEN);
    assertError(rerun("billing-retry", failedRun, billingSession), ErrorCode.RESOURCE_NOT_FOUND);
    probe.clear();
    rerun("ops-integration-check", failedRun, opsSession)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value(failedRun))
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.attemptCount").value(2));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT last_check_result FROM integration_config WHERE id = ?", String.class, id))
        .isEqualTo("SUCCESS");
    assertError(
        rerun("ops-integration-check", failedRun, opsSession), ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("INTEGRATION_CHECKED")).isEqualTo(2);
    assertThat(auditCount("SYSTEM_JOB_RERUN")).isEqualTo(1);
  }

  @Test
  void 예약_공지_게시_실패는_다시_실행하면_현재_상태를_검사해_게시한다() throws Exception {
    String created =
        post(
                "/api/ops/announcements",
                "{\"title\":\"점검 안내\",\"content\":\"점검 예정입니다.\",\"audienceType\":\"ALL\","
                    + "\"startsAt\":\""
                    + at(Instant.now())
                    + "\",\"endsAt\":\""
                    + at(Instant.now().plusSeconds(86_400))
                    + "\",\"importance\":\"NORMAL\",\"marketing\":false}",
                opsSession)
            .andReturn()
            .getResponse()
            .getContentAsString();
    long id = Long.parseLong(created.replaceAll("(?s).*\"announcementId\":(\\d+).*", "$1"));
    post("/api/ops/announcements/" + id + "/submit-review", "", opsSession)
        .andExpect(status().isOk());
    post(
            "/api/ops/announcements/" + id + "/publish",
            "{\"scheduledAt\":\"" + at(Instant.now().plusSeconds(3600)) + "\"}",
            opsSession)
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    Instant scheduledAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
    jdbcTemplate.update(
        "UPDATE platform_announcement SET scheduled_at = ? WHERE id = ?",
        Timestamp.from(scheduledAt),
        id);
    long run =
        insertFailedRun(
            "ops-announcement-publish",
            "announcement-publish:" + id + ":" + scheduledAt.getEpochSecond());
    assertError(rerun("ops-announcement-publish", run, billingSession), ErrorCode.FORBIDDEN);
    rerun("ops-announcement-publish", run, opsSession)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM platform_announcement WHERE id = ?", String.class, id))
        .isEqualTo("PUBLISHED");

    long running =
        jdbcTemplate.queryForObject(
            "INSERT INTO system_job_run (job_name, idempotency_key, status) VALUES"
                + " ('ops-announcement-publish', 'announcement-publish:1:1', 'RUNNING') RETURNING"
                + " id",
            Long.class);
    assertError(
        rerun("ops-announcement-publish", running, opsSession), ErrorCode.INVALID_TRANSITION);
    long other = insertFailedRun("billing-trial-ending-notice", "trial-ending-notice:1:1:D7");
    assertError(rerun("billing-trial-ending-notice", other, opsSession), ErrorCode.FORBIDDEN);
    assertError(rerun("billing-trial-ending-notice", other, billingSession), ErrorCode.FORBIDDEN);
    mockMvc
        .perform(get(JOBS).param("jobName", "billing-trial-ending-notice").cookie(opsSession))
        .andExpect(jsonPath("$.items[0].jobType").value("OTHER"))
        .andExpect(jsonPath("$.items[0].rerunRoles.length()").value(0));
  }

  @Test
  void 실패한_청구_작업은_결제담당이_다시_실행하고_이미_갱신된_구독은_다시_청구하지_않는다() throws Exception {
    BillingFixtures fixtures = new BillingFixtures(jdbcTemplate);
    fixtures.standardPrice("2026-12-STD", Instant.now().minus(400, ChronoUnit.DAYS));
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    Instant periodEnd = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
    long subscriptionId =
        fixtures
            .subscription(owner.office().getId())
            .status("ACTIVE")
            .period(periodEnd.minus(30, ChronoUnit.DAYS), periodEnd)
            .insert();
    fixtures.consent(
        subscriptionId, owner.user().getId(), owner.office().getId(), 39_900, 3_627, 39_900);
    fixtures.billingCustomerWithKey(
        owner.office().getId(),
        "customer-" + owner.office().getId(),
        cipher.encrypt("billing-key"));
    long run =
        insertFailedRun(
            "billing-renewal", "renewal:" + subscriptionId + ":" + periodEnd.getEpochSecond());

    assertError(rerun("billing-renewal", run, opsSession), ErrorCode.FORBIDDEN);
    Operator revoked = operator("회수결제", OperatorRole.BILLING);
    Cookie revokedSession = sessionOf(revoked);
    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", revoked.id());
    assertError(rerun("billing-renewal", run, revokedSession), ErrorCode.FORBIDDEN);

    rerun("billing-renewal", run, billingSession)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("SUCCESS"))
        .andExpect(jsonPath("$.attemptCount").value(2));
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    Timestamp renewedEnd =
        jdbcTemplate.queryForObject(
            "SELECT current_period_end FROM subscription WHERE id = ?",
            Timestamp.class,
            subscriptionId);
    assertThat(renewedEnd.toInstant()).isAfter(Instant.now());

    long stale = insertFailedRun("billing-renewal", "renewal:" + subscriptionId + ":1");
    rerun("billing-renewal", stale, billingSession)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("SUCCESS"));
    assertThat(count("SELECT count(*) FROM billing_charge_attempt")).isEqualTo(1);
    long broken = insertFailedRun("billing-retry", "retry:999999:1");
    assertError(rerun("billing-retry", broken, billingSession), ErrorCode.INVALID_TRANSITION);
    assertFieldError(
        post(JOBS + "/billing-retry/rerun", "{}", billingSession), "runId", "REQUIRED");
    assertThat(auditCount("SYSTEM_JOB_RERUN")).isEqualTo(2);
  }
}
