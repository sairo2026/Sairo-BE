package com.sairo.be.scenario;

import static com.sairo.be.support.AuthFlowSupport.json;
import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.ops.service.LegalRetentionJobService;
import com.sairo.be.domain.ops.service.TotpService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TextCipher;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.BillingFixtures;
import com.sairo.be.support.fixture.PolicyFixtures;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * 출시 필수 시나리오 ⑩ 개인정보 권리 요청, ⑪ 운영 출시 활성화 전제, ⑫ 보관 기한 정리부터 다른 운영자 검증까지의 파기를 실제 엔드포인트로 잇는다. 운영자 계정·세션과
 * 시간 경과, 범위 밖 전제(사무소·직원·구독·고객)만 시험 데이터로 만든다.
 */
@IntegrationTest
class LaunchScenarioOpsIntegrationTest extends VisitOutcomeTestBase {

  private static final String OPS_COOKIE = "__Host-sairo_ops_session";
  private static final String QUEUE = "/api/ops/deletion-queue";
  private static final String VERIFY_ALL =
      "{\"storageResult\":\"SUCCESS\",\"searchIndexResult\":\"SUCCESS\",\"fileResult\":\"SUCCESS\","
          + "\"temporaryExportResult\":\"SUCCESS\",\"evidence\":\"파기 결과 대조 기록\"}";

  @Autowired private TotpService totpService;
  @Autowired private TextCipher textCipher;
  @Autowired private LegalRetentionJobService retentionJobs;

  private record Ops(long id, String secret, Cookie cookie) {}

  private Ops operator(String name, OperatorRole... roles) {
    String secret = totpService.newSecret();
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (?, ?, 'ACTIVE', true, ?) RETURNING id",
            Long.class,
            "scenario-" + name + "-" + System.nanoTime(),
            name,
            textCipher.encrypt(secret));
    for (OperatorRole role : roles) {
      jdbcTemplate.update(
          "INSERT INTO operator_role_assignment (operator_account_id, role) VALUES (?, ?)",
          id,
          role.name());
    }
    String sessionId =
        sessions.createOperatorSession(
            new OperatorPrincipal(id, name, List.of(roles)), Instant.now(), Instant.now());
    Cookie cookie =
        new Cookie(
            OPS_COOKIE,
            Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
    return new Ops(id, secret, cookie);
  }

  /** 운영자 재인증은 실제 OTP 확인 엔드포인트로 받는다. */
  private void stepUp(Ops ops) throws Exception {
    String code = totpService.codeAt(ops.secret(), Instant.now().getEpochSecond() / 30);
    mockMvc
        .perform(jsonPost("/api/ops/auth/step-up", "{\"otpCode\":\"" + code + "\"}", ops.cookie()))
        .andExpect(status().isOk());
  }

  private ResultActions opsPost(String path, String body, Ops ops) throws Exception {
    return mockMvc.perform(jsonPost(path, body, ops.cookie()));
  }

  /** 권리 요청 처리 요청에는 저장된 현재 version을 If-Match로 붙인다. */
  private ResultActions opsProcess(String path, String body, Ops ops) throws Exception {
    Long version =
        jdbcTemplate.queryForObject(
            "SELECT version FROM privacy_request WHERE id = ?",
            Long.class,
            Long.valueOf(path.replaceAll(".*/privacy-requests/(\\d+)/process", "$1")));
    return mockMvc.perform(jsonPost(path, body, ops.cookie()).header("If-Match", version));
  }

  private ResultActions opsGet(String path, Ops ops) throws Exception {
    return mockMvc.perform(get(path).cookie(ops.cookie()));
  }

  private static String at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(9)).toString();
  }

  @Test
  void 시나리오10_비회원_권리_요청은_접수부터_본인확인_책임_사무소_기한_처리_이력_증빙_완료까지_이어진다() throws Exception {
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    long customer = insertCustomer(owner.office().getId(), owner.membership().getId(), "이수요");
    long coordinationId =
        createCoordination(
                cookie,
                customer,
                null,
                today().plusDays(5),
                today().plusDays(15),
                newProperty(cookie))
            .get("coordinationId")
            .longValue();
    String customerToken = issueToken(cookie, coordinationId);
    Ops privacy = operator("최개인", OperatorRole.PRIVACY);

    // 1. 고객 링크로 비회원 권리 요청을 접수한다.
    JsonNode received =
        json(
            mockMvc
                .perform(
                    jsonPost(
                        "/api/public/privacy-intakes",
                        "{\"linkKind\":\"CUSTOMER\",\"token\":\""
                            + customerToken
                            + "\",\"requestType\":\"ACCESS\",\"requesterRole\":\"SELF\","
                            + "\"contactChannel\":\"EMAIL\",\"contact\":\"hong@example.com\","
                            + "\"requestNote\":\"제가 입력한 방문 가능 시간을 열람하고 싶습니다\","
                            + "\"acknowledgedNoticeVersion\":\""
                            + NOTICE_VERSION
                            + "\"}"))
                .andExpect(status().isCreated())
                .andReturn());
    assertThat(
            Duration.between(
                OffsetDateTime.parse(received.get("receivedAt").asString()),
                OffsetDateTime.parse(received.get("resultDueAt").asString())))
        .isEqualTo(Duration.ofDays(10));

    // 2. 운영자가 접수 목록에서 찾아 본인확인 결과와 함께 권리 요청으로 전환한다.
    JsonNode intakes =
        json(
            opsGet("/api/ops/guest-privacy-intakes", privacy)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(intakes.get("items")).hasSize(1);
    JsonNode intake = intakes.get("items").get(0);
    assertThat(intake.get("officeId").longValue()).isEqualTo(owner.office().getId());
    assertThat(intake.toString()).doesNotContain("hong@example.com");
    long intakeId = intake.get("intakeId").longValue();
    JsonNode converted =
        json(
            opsPost(
                    "/api/ops/guest-privacy-intakes/" + intakeId + "/resolve",
                    "{\"verificationResult\":\"VERIFIED\",\"action\":\"CONVERT\","
                        + "\"legalBasis\":\"개인정보 보호법 제35조\","
                        + "\"requestScope\":\"고객 링크로 제출한 방문 가능 시간 열람\"}",
                    privacy)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(converted.get("status").asString()).isEqualTo("CONVERTED");
    long requestId = converted.get("privacyRequestId").longValue();
    String detailPath = "/api/ops/privacy-requests/" + requestId;
    JsonNode opened = json(opsGet(detailPath, privacy).andExpect(status().isOk()).andReturn());
    assertThat(opened.get("identityStatus").asString()).isEqualTo("VERIFIED");
    assertThat(opened.get("officeId").longValue()).isEqualTo(owner.office().getId());
    assertThat(opened.get("handlerType").asString()).isEqualTo("OFFICE");
    assertThat(opened.get("legalBasis").asString()).isEqualTo("개인정보 보호법 제35조");
    OffsetDateTime receivedAt = OffsetDateTime.parse(opened.get("receivedAt").asString());
    assertThat(
            Duration.between(receivedAt, OffsetDateTime.parse(opened.get("legalDueAt").asString())))
        .isEqualTo(Duration.ofDays(10));
    assertThat(
            Duration.between(
                receivedAt, OffsetDateTime.parse(opened.get("internalDueAt").asString())))
        .isEqualTo(Duration.ofDays(7));
    assertThat(opened.get("status").asString()).isEqualTo("RECEIVED");

    // 3. 전달 → 보완 → 협조 → 결정 → 실행 → 증빙과 함께 완료.
    String process = detailPath + "/process";
    List<String[]> steps =
        List.of(
            new String[] {"{\"action\":\"FORWARD\",\"note\":\"책임 사무소에 전달\"}", "FORWARDED"},
            new String[] {
              "{\"action\":\"REQUEST_SUPPLEMENT\",\"note\":\"요청 범위를 확인해 주세요\"}", "WAITING_FOR_INFO"
            },
            new String[] {
              "{\"action\":\"REQUEST_COOPERATION\",\"note\":\"보완 자료 접수\"}", "REVIEWING"
            },
            new String[] {"{\"action\":\"APPROVE\",\"note\":\"열람 범위 확인\"}", "APPROVED"},
            new String[] {"{\"action\":\"EXECUTE\"}", "EXECUTING"});
    for (String[] step : steps) {
      opsProcess(process, step[0], privacy)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value(step[1]));
    }
    assertFieldError(
        opsProcess(process, "{\"action\":\"COMPLETE\",\"note\":\"열람 자료 제공\"}", privacy),
        "evidenceReference",
        "REQUIRED");
    opsProcess(
            process,
            "{\"action\":\"COMPLETE\",\"note\":\"열람 자료 제공\",\"evidenceReference\":\"PRV-2026-001\"}",
            privacy)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));

    JsonNode done = json(opsGet(detailPath, privacy).andExpect(status().isOk()).andReturn());
    assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(done.get("completionEvidence").asString()).isEqualTo("PRV-2026-001");
    assertThat(done.get("decisionReason").asString()).isEqualTo("열람 범위 확인");
    assertThat(done.get("completedAt").isNull()).isFalse();
    assertThat(done.get("dueState").isNull()).isTrue();
    assertThat(done.get("allowedActions")).isEmpty();
    List<String> eventTypes = new ArrayList<>();
    done.get("events").forEach(event -> eventTypes.add(event.get("eventType").asString()));
    assertThat(eventTypes)
        .containsSubsequence(
            "FORWARD",
            "REQUEST_SUPPLEMENT",
            "REQUEST_COOPERATION",
            "APPROVE",
            "EXECUTE",
            "COMPLETE");
    assertError(
        opsProcess(process, "{\"action\":\"FORWARD\"}", privacy), ErrorCode.INVALID_TRANSITION);
    assertThat(
            count(
                "SELECT count(*) FROM ops_audit_log WHERE action_type = 'PRIVACY_REQUEST_PROCESSED'"))
        .isEqualTo(6);
    // 요청자 알림·처리 결과 통지는 기능이 없어 확인하지 않는다(ALIGN66 남은 문제 3번).
  }

  @Test
  void 시나리오11_정책_게시와_가격_인상_동의와_회사_정보_수신_시험을_마쳐야_운영_연동을_켤_수_있다() throws Exception {
    Instant now = Instant.now();
    Ops ops = operator("최운영", OperatorRole.OPS);
    Ops reviewer = operator("한운영", OperatorRole.OPS);
    Ops billing = operator("박결제", OperatorRole.BILLING);
    Ops billingApprover = operator("이결제", OperatorRole.BILLING);
    BillingFixtures fixtures = new BillingFixtures(jdbcTemplate);
    fixtures.purchaseTerms(BillingFixtures.PURCHASE_TERMS_VERSION);
    fixtures.standardPrice("LAUNCH-OLD-STD", now.minus(Duration.ofDays(100)));
    TestData.Staff owner = newStaff(OfficeMembershipRole.OWNER);
    fixtures
        .subscription(owner.office().getId())
        .status("ACTIVE")
        .period(now.minus(Duration.ofDays(5)), now.plus(Duration.ofDays(25)))
        .insert();
    // 운영 연동 행은 만드는 API가 없어 배포 책임자가 넣는 행을 시험 데이터로 둔다(ALIGN68 남은 문제 5번).
    long integrationId =
        jdbcTemplate.queryForObject(
            "INSERT INTO integration_config (environment, provider, function_code,"
                + " secret_reference_id, masked_connection, retention_policy, contract_status,"
                + " consent_status) VALUES ('PRODUCTION', 'pg-a', 'PAYMENT', 'secret-ref-example',"
                + " 'ac***01', '계약 종료 뒤 파기', 'PENDING', 'PENDING') RETURNING id",
            Long.class);
    String enable =
        "{\"enabled\":true,\"contractStatus\":\"SIGNED\",\"consentStatus\":\"GRANTED\","
            + "\"contractNoticeReviewed\":true}";
    String integrationPath = "/api/ops/integrations/" + integrationId;
    stepUp(ops);
    stepUp(billing);
    stepUp(billingApprover);

    // 0. 아직 준비가 안 됐으면 운영 연동을 켤 수 없다.
    mockMvc
        .perform(get("/api/ops/company-service-info").cookie(ops.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.launchReady").value(false));
    assertError(
        mockMvc.perform(jsonPatch(integrationPath, enable, ops.cookie())),
        ErrorCode.INTEGRATION_PREREQUISITE_INCOMPLETE);

    // 1. 정책 본문 새 버전 작성 → 다른 운영자 검토 → 게시.
    String policyBody =
        "{\"docType\":\"TERMS\",\"version\":\"2027-LAUNCH\",\"body\":\"제1조 목적\\n이 약관은 서비스 이용"
            + " 조건을 정한다.\",\"changeSummary\":\"출시 판 정리\",\"noticeAt\":\""
            + at(now)
            + "\",\"requiresConsent\":false,\"effectiveAt\":\""
            + at(now.plus(Duration.ofDays(10)))
            + "\"}";
    long documentId =
        json(opsPost("/api/ops/policy-documents", policyBody, ops)
                .andExpect(status().isCreated())
                .andReturn())
            .get("documentId")
            .longValue();
    assertError(
        opsPost("/api/ops/policy-documents/" + documentId + "/publish", "", ops),
        ErrorCode.INVALID_TRANSITION);
    opsPost("/api/ops/policy-documents/" + documentId + "/submit-review", "", reviewer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REVIEW"));
    opsPost("/api/ops/policy-documents/" + documentId + "/publish", "", ops)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
    mockMvc
        .perform(get("/api/ops/company-service-info").cookie(ops.cookie()))
        .andExpect(
            jsonPath("$.missingItems[?(@ == 'POLICY_DOCUMENTS_NOT_PUBLISHED')]").isNotEmpty());
    PolicyFixtures.publish(jdbcTemplate, "PRIVACY", ops.id());
    PolicyFixtures.publish(jdbcTemplate, "REFUND", ops.id());
    mockMvc
        .perform(get("/api/ops/company-service-info").cookie(ops.cookie()))
        .andExpect(jsonPath("$.missingItems[?(@ == 'POLICY_DOCUMENTS_NOT_PUBLISHED')]").isEmpty());

    // 2. 인상 요금표 등록 → 다른 결제담당 승인 → 30일 이상 앞선 인상 안내 → 대표의 새 금액 동의.
    String priceBody =
        "{\"soloAmount\":45000,\"teamFirstExtra\":10000,\"teamAfterFourExtra\":8000,"
            + "\"effectiveAt\":\""
            + at(now.plus(Duration.ofDays(45)))
            + "\",\"noticeAt\":\""
            + at(now)
            + "\"}";
    long scheduleId =
        json(opsPost("/api/ops/price-schedules", priceBody, billing)
                .andExpect(status().isCreated())
                .andReturn())
            .get("scheduleId")
            .longValue();
    assertError(
        opsPost("/api/ops/price-schedules/" + scheduleId + "/approve", "", billing),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    opsPost("/api/ops/price-schedules/" + scheduleId + "/approve", "", billingApprover)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    opsPost("/api/ops/price-schedules/" + scheduleId + "/notices", "{}", billing)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.createdCount").value(1));

    Cookie ownerCookie = cookieOf(owner);
    JsonNode notices =
        json(
            mockMvc
                .perform(get("/api/subscriptions/price-change-notices").cookie(ownerCookie))
                .andExpect(status().isOk())
                .andReturn());
    JsonNode notice = notices.get("items").get(0);
    assertThat(notice.get("changeKind").asString()).isEqualTo("INCREASE");
    assertThat(notice.get("decision").asString()).isEqualTo("PENDING");
    long noticeId = notice.get("noticeId").longValue();
    assertThat(
            count(
                "SELECT count(*) FROM pricing_change_notice WHERE id = ? AND effective_at >="
                    + " notified_at + interval '30 days' AND applies_from_period_start >= effective_at",
                noticeId))
        .isEqualTo(1);
    JsonNode preview =
        json(
            mockMvc
                .perform(
                    get("/api/subscriptions/purchase-consent/preview?priceChangeNoticeId="
                            + noticeId)
                        .cookie(ownerCookie))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(preview.get("priceVersion").asString())
        .isNotEqualTo("LAUNCH-OLD-STD")
        .endsWith("-STD");
    String consent =
        "{\"termsVersion\":\""
            + preview.get("termsVersion").asString()
            + "\",\"plan\":\""
            + preview.get("plan").asString()
            + "\",\"memberCount\":"
            + preview.get("memberCount").asInt()
            + ",\"priceVersion\":\""
            + preview.get("priceVersion").asString()
            + "\",\"priceChangeNoticeId\":"
            + noticeId
            + ",\"totalAmountInclVat\":"
            + preview.get("totalAmountInclVat").asLong()
            + ",\"amountLimit\":"
            + preview.get("amountLimit").asLong()
            + ",\"periodStart\":\""
            + preview.get("periodStart").asString()
            + "\",\"periodEnd\":\""
            + preview.get("periodEnd").asString()
            + "\",\"nextChargeAt\":\""
            + preview.get("nextChargeAt").asString()
            + "\",\"agreed\":true}";
    mockMvc
        .perform(jsonPost("/api/subscriptions/purchase-consent", consent, ownerCookie))
        .andExpect(status().isOk());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT decision FROM pricing_change_notice WHERE id = ?", String.class, noticeId))
        .isEqualTo("AGREED");

    // 3. 회사 정보 작성 → 다른 운영자의 문의 수신 시험 성공(게시) → 출시 준비 판정.
    String infoBody =
        "{\"businessName\":\"사이로\",\"representativeName\":\"대표자\",\"businessNumber\":\"000-00-00000\","
            + "\"address\":\"사업장 주소\",\"contactPhone\":\"02-0000-0000\",\"ecommerceNotice\":null,"
            + "\"supportEmail\":\"support@example.com\",\"supportHours\":\"평일 09:00-18:00\","
            + "\"emergencyContact\":\"02-0000-0001\",\"privacyOfficer\":\"개인정보 담당\","
            + "\"privacyContact\":\"privacy@example.com\",\"privacyRequestPath\":\"설정 > 개인정보 요청\","
            + "\"cancellationPath\":\"설정 > 구독 > 해지 예약\"}";
    long infoId =
        json(opsPost("/api/ops/company-service-info", infoBody, ops)
                .andExpect(status().isCreated())
                .andReturn())
            .get("infoId")
            .longValue();
    assertError(
        mockMvc.perform(jsonPatch(integrationPath, enable, ops.cookie())),
        ErrorCode.INTEGRATION_PREREQUISITE_INCOMPLETE);
    opsPost(
            "/api/ops/company-service-info/" + infoId + "/inquiry-test",
            "{\"result\":\"SUCCESS\"}",
            reviewer)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.publishedAt").isNotEmpty());
    mockMvc
        .perform(get("/api/ops/company-service-info").cookie(ops.cookie()))
        .andExpect(jsonPath("$.launchReady").value(true))
        .andExpect(jsonPath("$.missingItems").isEmpty());
    mockMvc
        .perform(get("/api/public/company-info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supportEmail").value("support@example.com"));

    // 4. 운영 연동 활성화.
    mockMvc
        .perform(jsonPatch(integrationPath, enable, ops.cookie()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT approved_by_operator_id FROM integration_config WHERE id = ?",
                Long.class,
                integrationId))
        .isEqualTo(ops.id());
    // 활성화 판정은 회사 정보만 본다. 정책 게시와 가격 안내 완료는 판정 조건이 아니다(기록 공백).
  }

  @Test
  void 시나리오12_보관_기한_정리부터_제외분_미리보기_저장소별_실행_다른_운영자_검증까지_끝난다() throws Exception {
    jdbcTemplate.execute(
        new ClassPathResource("db/migration/V15__seed_terms_catalog_and_legal_retention_rules.sql")
            .getContentAsString(StandardCharsets.UTF_8));
    assertThat(
            count(
                "SELECT count(*) FROM legal_retention_rule WHERE rule_code IN ('LOGIN_HISTORY',"
                    + " 'KAKAO_UNLINK_EVENT', 'GUEST_INPUT_AFTER_APPROVAL_END')"))
        .isEqualTo(3);
    SelectionFixture visit = confirmed();
    long officeId = visit.base().owner().office().getId();
    long userId = visit.base().owner().user().getId();
    long approvalId = visit.base().approvalId();
    long linkId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM visit_customer_link WHERE coordination_id = ? ORDER BY id LIMIT 1",
            Long.class,
            visit.coordinationId());
    // 시간 경과: 고객 링크와 철회된 출입승인의 보관 기한이 지났고 1년 넘은 로그인 기록이 있다.
    jdbcTemplate.update(
        "UPDATE visit_customer_link SET retention_until = current_date - 1 WHERE id = ?", linkId);
    jdbcTemplate.update(
        "UPDATE property_access_approval SET revoked_at = now() - interval '100 days',"
            + " revocation_reason = '철회', revocation_recorded_at = now() - interval '100 days',"
            + " revoked_by_user_id = ?, retention_until = current_date - 1 WHERE id = ?",
        userId,
        approvalId);
    jdbcTemplate.update(
        "INSERT INTO login_history (user_id, auth_method, created_at) VALUES (?, 'EMAIL', now() -"
            + " interval '400 days')",
        userId);
    Ops executor = operator("실행담당", OperatorRole.PRIVACY);
    Ops verifier = operator("검증담당", OperatorRole.PRIVACY);

    // 1. 매일 배치(보관 기한 정리)가 기한 지난 데이터군을 파기 큐에 올린다.
    assertThat(retentionJobs.runScheduled(LegalRetentionJobService.SWEEP_JOB, Instant.now()))
        .isTrue();
    JsonNode listed = json(opsGet(QUEUE, executor).andExpect(status().isOk()).andReturn());
    Map<String, Long> queueByType = new HashMap<>();
    listed
        .get("items")
        .forEach(
            item ->
                queueByType.put(
                    item.get("targetType").asString(), item.get("queueId").longValue()));
    assertThat(queueByType.keySet())
        .containsExactlyInAnyOrder("GUEST_LINK_INPUT", "APPROVER_PERSONAL", "LOGIN_HISTORY");
    long linkQueue = queueByType.get("GUEST_LINK_INPUT");
    long approverQueue = queueByType.get("APPROVER_PERSONAL");
    long loginQueue = queueByType.get("LOGIN_HISTORY");

    // 2. 파기 예정 대상과 제외분 미리보기. 로그인 기록은 다른 운영자 승인으로 보존 예외를 둔다.
    JsonNode linkPreview =
        json(
            opsGet(QUEUE + "/" + linkQueue + "/preview", executor)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(linkPreview.get("targetCount").longValue()).isEqualTo(1);
    assertThat(linkPreview.get("holdReasons")).isEmpty();
    OffsetDateTime hold = OffsetDateTime.now().plusMonths(3);
    String exclude =
        "{\"exclusionReason\":\"분쟁 진행 중\",\"holdExpiresAt\":\""
            + hold
            + "\",\"exclusionReviewedAt\":\""
            + hold.minusMonths(1)
            + "\",\"approvedByOperatorId\":";
    assertError(
        opsPost(QUEUE + "/" + loginQueue + "/exclude", exclude + executor.id() + "}", executor),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    opsPost(QUEUE + "/" + loginQueue + "/exclude", exclude + verifier.id() + "}", executor)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result").value("EXCLUDED"));
    JsonNode loginPreview =
        json(
            opsGet(QUEUE + "/" + loginQueue + "/preview", executor)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(loginPreview.get("excludedCount").longValue())
        .isEqualTo(loginPreview.get("targetCount").longValue());
    assertThat(loginPreview.get("holdReasons").toString()).contains("분쟁 진행 중");

    // 3. 재인증 뒤 저장소별 실행. 실행 전에는 검증할 수 없다.
    assertError(
        opsPost(QUEUE + "/" + linkQueue + "/execute", "", executor), ErrorCode.STEP_UP_REQUIRED);
    assertError(
        opsPost(QUEUE + "/" + linkQueue + "/verify", VERIFY_ALL, verifier),
        ErrorCode.INVALID_TRANSITION);
    stepUp(executor);
    for (long queueId : List.of(linkQueue, approverQueue)) {
      opsPost(QUEUE + "/" + queueId + "/execute", "", executor)
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.storageResult").value("SUCCESS"))
          .andExpect(jsonPath("$.result").value(org.hamcrest.Matchers.nullValue()));
    }
    assertError(
        opsPost(QUEUE + "/" + loginQueue + "/execute", "", executor), ErrorCode.INVALID_TRANSITION);
    assertThat(count("SELECT count(*) FROM visit_customer_link WHERE id = ?", linkId)).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM property_access_approval WHERE id = ? AND anonymized_at IS NOT"
                    + " NULL",
                approvalId))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM login_history WHERE user_id = ?", userId)).isEqualTo(1);

    // 4. 실행자는 검증할 수 없고 다른 운영자가 저장소별 결과를 검증해야 완료된다.
    for (long queueId : List.of(linkQueue, approverQueue)) {
      assertError(
          opsPost(QUEUE + "/" + queueId + "/verify", VERIFY_ALL, executor),
          ErrorCode.SELF_APPROVAL_FORBIDDEN);
      assertError(
          opsPost(
              QUEUE + "/" + queueId + "/verify",
              "{\"storageResult\":\"SUCCESS\",\"evidence\":\"로그\"}",
              verifier),
          ErrorCode.VERIFICATION_INCOMPLETE);
      opsPost(QUEUE + "/" + queueId + "/verify", VERIFY_ALL, verifier)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.result").value("SUCCESS"))
          .andExpect(jsonPath("$.verifiedByOperatorId").value(verifier.id()));
    }
    JsonNode done =
        json(opsGet(QUEUE + "?state=SUCCESS", executor).andExpect(status().isOk()).andReturn());
    assertThat(done.get("totalElements").longValue()).isEqualTo(2);
    assertThat(coordinationStatus(visit.coordinationId())).isEqualTo("CONFIRMED");
    assertThat(
            count(
                "SELECT count(*) FROM ops_audit_log WHERE action_type IN ('DELETION_QUEUE_EXECUTED',"
                    + " 'DELETION_QUEUE_VERIFIED')"))
        .isEqualTo(4);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM system_job_run WHERE job_name = 'retention-sweep'",
                String.class))
        .isEqualTo("SUCCESS");
  }
}
