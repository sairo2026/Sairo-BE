package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.legal.service.KakaoUnlinkDispatchService;
import com.sairo.be.domain.legal.service.RetentionRuleLookup;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.domain.ops.service.OpsLegalOpsService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.stub.StubKakaoUnlink;
import com.sairo.be.global.port.stub.StubTermsDocumentReader;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class OpsLegalOpsIntegrationTest extends OpsConsoleTestBase {

  private static final String HASH = "/api/ops/terms-hash-checks";
  private static final String RULES = "/api/ops/retention-rules";
  private static final String UNLINK = "/api/ops/kakao-unlink-events";

  @Autowired private StubTermsDocumentReader reader;
  @Autowired private StubKakaoUnlink kakaoUnlink;
  @Autowired private OpsLegalOpsService legalOpsService;
  @Autowired private KakaoUnlinkDispatchService dispatchService;
  @Autowired private RetentionRuleLookup retentionRuleLookup;

  private Operator ops;
  private Cookie opsSession;
  private Operator privacy;
  private Cookie privacySession;

  @BeforeEach
  void setUp() {
    ops = operator("최운영", OperatorRole.OPS);
    opsSession = sessionOf(ops);
    privacy = operator("정개인", OperatorRole.PRIVACY);
    privacySession = sessionOf(privacy);
    stepUp(privacySession);
    reader.clear();
    kakaoUnlink.clear();
  }

  @AfterEach
  void tearDown() {
    reader.clear();
    kakaoUnlink.clear();
  }

  private static String sha256(String body) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
  }

  private void insertTerms(String code, String version, String hash, Instant effectiveAt) {
    jdbcTemplate.update(
        "INSERT INTO terms_catalog (terms_code, terms_version, title, category, required,"
            + " document_url, content_hash, effective_at) VALUES (?, ?, '약관', 'TERMS', true, ?,"
            + " ?, ?)",
        code,
        version,
        "/legal/" + code.toLowerCase() + "/" + version,
        hash,
        Timestamp.from(effectiveAt));
  }

  private long insertRule(String code) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO legal_retention_rule (rule_code, data_group_label, retention_days,"
            + " anchor_event, basis_note, status, effective_from) VALUES (?, '비회원 링크 입력 데이터',"
            + " 90, 'APPROVAL_END', '내부 권장안', 'ASSUMPTION', DATE '2026-11-09') RETURNING id",
        Long.class,
        code);
  }

  private ResultActions putRule(String code, String ifMatch, String body, Cookie cookie)
      throws Exception {
    MockHttpServletRequestBuilder builder =
        put(RULES + "/" + code).contentType(MediaType.APPLICATION_JSON).content(body);
    if (ifMatch != null) {
      builder.header("If-Match", ifMatch);
    }
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return mockMvc.perform(AuthFlowSupport.withCsrf(builder));
  }

  private static String ruleBody(int days, String status, String reason) {
    return "{\"retentionDays\":"
        + days
        + ",\"basisNote\":\"변호사 확인 반영\",\"status\":\""
        + status
        + "\""
        + (reason == null ? "" : ",\"changeReason\":\"" + reason + "\"")
        + "}";
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_맞지_않으면_403이다() throws Exception {
    Cookie support = sessionOf(operator("김지원", OperatorRole.SUPPORT));
    for (String path : new String[] {HASH, RULES, UNLINK}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(support)), ErrorCode.FORBIDDEN);
    }
    assertError(mockMvc.perform(get(HASH).cookie(privacySession)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get(RULES).cookie(opsSession)), ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get(UNLINK).cookie(opsSession)), ErrorCode.FORBIDDEN);
    assertError(post(HASH, "", null), ErrorCode.UNAUTHENTICATED);
    assertError(post(HASH, "", privacySession), ErrorCode.FORBIDDEN);
    assertError(post(UNLINK + "/1/retry", "", null), ErrorCode.UNAUTHENTICATED);
    assertError(post(UNLINK + "/1/retry", "", opsSession), ErrorCode.FORBIDDEN);
    String body = ruleBody(90, "ASSUMPTION", "사유");
    assertError(putRule("LOGIN_HISTORY", "1", body, null), ErrorCode.UNAUTHENTICATED);
    assertError(putRule("LOGIN_HISTORY", "1", body, opsSession), ErrorCode.FORBIDDEN);
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    Cookie member = memberSession(owner);
    for (String path : new String[] {HASH, RULES, UNLINK}) {
      assertError(mockMvc.perform(get(path).cookie(member)), ErrorCode.UNAUTHENTICATED);
    }
    assertError(post(UNLINK + "/999999/retry", "", privacySession), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(putRule("NO_SUCH_RULE", "1", body, privacySession), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 해시_점검은_시행_중이거나_예정인_버전만_대조하고_불일치와_읽기_오류를_기록한다() throws Exception {
    Instant now = Instant.now();
    String tosBody = "사이로 이용약관\n제1조 목적";
    insertTerms("TOS", "0.9", sha256("옛 약관"), now.minus(400, ChronoUnit.DAYS));
    insertTerms("TOS", "1.0", sha256(tosBody), now.minus(10, ChronoUnit.DAYS));
    insertTerms("TOS", "2.0", sha256("새 약관"), now.plus(20, ChronoUnit.DAYS));
    insertTerms("PRIVACY", "1.0", sha256("개인정보처리방침"), now.minus(10, ChronoUnit.DAYS));
    insertTerms("MARKETING", "1.0", sha256("마케팅 동의"), now.minus(10, ChronoUnit.DAYS));
    reader.put("TOS", "1.0", tosBody);
    reader.put("TOS", "2.0", "새 약관");
    reader.put("MARKETING", "1.0", "바뀐 마케팅 동의");

    mockMvc
        .perform(get(HASH).cookie(opsSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4))
        .andExpect(jsonPath("$.items[?(@.version == '0.9')]").isEmpty())
        .andExpect(
            jsonPath("$.items[?(@.termsCode == 'TOS' && @.version == '2.0')].status")
                .value("SCHEDULED"))
        .andExpect(jsonPath("$.items[0].lastResult").value(Matchers.nullValue()));

    post(HASH, "", opsSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mismatchCount").value(1))
        .andExpect(jsonPath("$.errorCount").value(1))
        .andExpect(jsonPath("$.items[?(@.termsCode == 'MARKETING')].result").value("MISMATCH"))
        .andExpect(
            jsonPath("$.items[?(@.termsCode == 'PRIVACY')].actualHashPrefix")
                .value(Matchers.contains(Matchers.nullValue())))
        .andExpect(
            jsonPath("$.items[?(@.termsCode == 'TOS' && @.version == '1.0')].expectedHashPrefix")
                .value(sha256(tosBody).substring(0, 8)));
    mockMvc
        .perform(get(HASH).cookie(opsSession))
        .andExpect(jsonPath("$.items[?(@.termsCode == 'PRIVACY')].lastResult").value("ERROR"))
        .andExpect(
            jsonPath("$.items[?(@.termsCode == 'TOS' && @.version == '1.0')].lastResult")
                .value("MATCH"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT last_hash_check_result FROM terms_catalog WHERE terms_code = 'TOS' AND"
                    + " terms_version = '0.9'",
                String.class))
        .isNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT detail FROM ops_audit_log WHERE action_type = 'TERMS_HASH_CHECK_RUN'",
                String.class))
        .contains("MARKETING:1.0 MISMATCH")
        .contains("PRIVACY:1.0 ERROR");

    legalOpsService.runScheduledTermsHashCheck(now);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, error_code, attempt_count FROM system_job_run WHERE job_name ="
                    + " 'ops-terms-hash-check'"))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "TERMS_HASH_MISMATCH");
    reader.put("PRIVACY", "1.0", "개인정보처리방침");
    reader.put("MARKETING", "1.0", "마케팅 동의");
    legalOpsService.runScheduledTermsHashCheck(now);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, attempt_count FROM system_job_run WHERE job_name ="
                    + " 'ops-terms-hash-check'"))
        .containsEntry("status", "SUCCESS")
        .containsEntry("attempt_count", 2);
    legalOpsService.runScheduledTermsHashCheck(now);
    assertThat(count("SELECT count(*) FROM system_job_run WHERE job_name = 'ops-terms-hash-check'"))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM system_job_run WHERE job_name ="
                    + " 'ops-terms-hash-check'",
                String.class))
        .isEqualTo("terms-hash-check:" + TimeRules.displayDate(now));
  }

  @Test
  void 보관_기간_규칙은_버전과_변경_사유를_받아_고치고_감사_기록에_남긴다() throws Exception {
    long id = insertRule("GUEST_INPUT_AFTER_APPROVAL_END");
    insertRule("LOGIN_HISTORY");
    mockMvc
        .perform(get(RULES).cookie(privacySession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].ruleCode").value("GUEST_INPUT_AFTER_APPROVAL_END"))
        .andExpect(jsonPath("$.items[0].retentionDays").value(90))
        .andExpect(jsonPath("$.items[0].effectiveFrom").value("2026-11-09"))
        .andExpect(jsonPath("$.items[0].version").value(1));

    String code = "GUEST_INPUT_AFTER_APPROVAL_END";
    assertFieldError(
        putRule(code, null, ruleBody(120, "LAWYER_CONFIRMED", "변호사 검토 답변 반영"), privacySession),
        "If-Match",
        "REQUIRED");
    assertFieldError(
        putRule(code, "1", ruleBody(0, "LAWYER_CONFIRMED", "사유"), privacySession),
        "retentionDays",
        "OUT_OF_RANGE");
    assertFieldError(
        putRule(code, "1", ruleBody(120, "CONFIRMED", "사유"), privacySession),
        "status",
        "NOT_ALLOWED");
    assertFieldError(
        putRule(code, "1", ruleBody(120, "LAWYER_CONFIRMED", null), privacySession),
        "changeReason",
        "REQUIRED");
    assertError(
        putRule(code, "2", ruleBody(120, "LAWYER_CONFIRMED", "사유"), privacySession),
        ErrorCode.VERSION_CONFLICT);

    putRule(code, "\"1\"", ruleBody(120, "LAWYER_CONFIRMED", "변호사 검토 답변 반영"), privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionDays").value(120))
        .andExpect(jsonPath("$.status").value("LAWYER_CONFIRMED"))
        .andExpect(jsonPath("$.basisNote").value("변호사 확인 반영"))
        .andExpect(
            jsonPath("$.effectiveFrom").value(TimeRules.displayDate(Instant.now()).toString()))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(retentionRuleLookup.retentionDays(code)).hasValue(120);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT updated_by_operator_id FROM legal_retention_rule WHERE id = ?",
                Long.class,
                id))
        .isEqualTo(privacy.id());
    assertError(
        putRule(code, "1", ruleBody(150, "LAWYER_CONFIRMED", "사유"), privacySession),
        ErrorCode.VERSION_CONFLICT);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT detail FROM ops_audit_log WHERE action_type = 'RETENTION_RULE_CHANGED'",
                String.class))
        .contains("90일→120일")
        .contains("변호사 검토 답변 반영");
  }

  @Test
  void 연결_끊기_사건은_식별자_없이_보이고_대기_재시도_상태의_호출만_수동_재시도한다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    long userId = owner.user().getId();
    Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
    long failing =
        jdbcTemplate.queryForObject(
            "INSERT INTO legal_kakao_unlink_event (direction, user_id, target_key_encrypted,"
                + " status, attempt_count, next_attempt_at, last_error_code, occurred_at) VALUES"
                + " ('OUTBOUND_CALL', ?, ?, 'FAILED_RETRYING', 3, now() + interval '2 hours',"
                + " 'KAKAO_TIMEOUT', ?) RETURNING id",
            Long.class,
            userId,
            textCipher.encrypt("kakao-user-example"),
            Timestamp.from(past));
    long done =
        jdbcTemplate.queryForObject(
            "INSERT INTO legal_kakao_unlink_event (direction, user_id, status, attempt_count,"
                + " completed_at, occurred_at) VALUES ('OUTBOUND_CALL', ?, 'SUCCEEDED', 1, now(),"
                + " ?) RETURNING id",
            Long.class,
            userId,
            Timestamp.from(past.minusSeconds(60)));
    long inbound =
        jdbcTemplate.queryForObject(
            "INSERT INTO legal_kakao_unlink_event (direction, kakao_key_hmac, hmac_key_version,"
                + " referrer_type, status, completed_at, occurred_at) VALUES ('INBOUND_WEBHOOK', ?,"
                + " 1, 'UNLINK_FROM_APPS', 'NO_MATCH', now(), ?) RETURNING id",
            Long.class,
            "a".repeat(64),
            Timestamp.from(past.minusSeconds(120)));

    String listed =
        mockMvc
            .perform(get(UNLINK).cookie(privacySession))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.items[0].eventId").value(failing))
            .andExpect(jsonPath("$.items[0].lastErrorCode").value("KAKAO_TIMEOUT"))
            .andExpect(jsonPath("$.items[2].referrerType").value("UNLINK_FROM_APPS"))
            .andExpect(jsonPath("$.items[2].userId").value(Matchers.nullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(listed).doesNotContain("kakao-user-example").doesNotContain("a".repeat(64));
    mockMvc
        .perform(
            get(UNLINK)
                .param("direction", "OUTBOUND_CALL")
                .param("status", "SUCCEEDED")
                .cookie(privacySession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].eventId").value(done));
    assertFieldError(
        mockMvc.perform(get(UNLINK).param("status", "DONE").cookie(privacySession)),
        "status",
        "NOT_ALLOWED");

    assertError(
        post(UNLINK + "/" + done + "/retry", "", privacySession), ErrorCode.INVALID_TRANSITION);
    assertError(
        post(UNLINK + "/" + inbound + "/retry", "", privacySession), ErrorCode.INVALID_TRANSITION);
    post(UNLINK + "/" + failing + "/retry", "", privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.attemptCount").value(3))
        .andExpect(jsonPath("$.nextAttemptAt").isNotEmpty());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT next_attempt_at <= now() FROM legal_kakao_unlink_event WHERE id = ?",
                Boolean.class,
                failing))
        .isTrue();
    dispatchService.dispatchDue();
    assertThat(kakaoUnlink.unlinked()).containsExactly("kakao-user-example");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM legal_kakao_unlink_event WHERE id = ?", String.class, failing))
        .isEqualTo("SUCCEEDED");
    assertError(
        post(UNLINK + "/" + failing + "/retry", "", privacySession), ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("KAKAO_UNLINK_RETRY_REQUESTED")).isEqualTo(1);
  }

  @Test
  void 보관_기간_규칙_수정은_재인증이_없거나_만료되면_401이고_재인증_뒤에는_고쳐진다() throws Exception {
    String code = "GUEST_INPUT_AFTER_APPROVAL_END";
    insertRule(code);
    Cookie fresh = sessionOf(operator("한개인", OperatorRole.PRIVACY));
    String body = ruleBody(120, "LAWYER_CONFIRMED", "변호사 검토 답변 반영");

    assertError(putRule(code, "1", body, fresh), ErrorCode.STEP_UP_REQUIRED);
    expireStepUp(fresh);
    assertError(putRule(code, "1", body, fresh), ErrorCode.STEP_UP_REQUIRED);
    assertThat(retentionRuleLookup.retentionDays(code)).hasValue(90);
    assertThat(auditCount("RETENTION_RULE_CHANGED")).isZero();
    assertError(putRule("NO_SUCH_RULE", "1", body, fresh), ErrorCode.RESOURCE_NOT_FOUND);

    stepUp(fresh);
    putRule(code, "1", body, fresh)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionDays").value(120))
        .andExpect(jsonPath("$.version").value(2));
  }
}
