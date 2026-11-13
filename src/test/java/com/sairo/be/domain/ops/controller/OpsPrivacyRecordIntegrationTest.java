package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.support.TimeRules;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@IntegrationTest
class OpsPrivacyRecordIntegrationTest extends OpsConsoleTestBase {

  private static final String INTAKES = "/api/ops/guest-privacy-intakes";
  private static final String AUTHORITY = "/api/ops/authority-requests";
  private static final String SHA = "ab".repeat(32);

  private Operator privacy;
  private Cookie privacySession;
  private Operator security;
  private Cookie securitySession;
  private TestData.Staff owner;

  @BeforeEach
  void setUp() {
    privacy = operator("최개인", OperatorRole.PRIVACY);
    privacySession = sessionOf(privacy);
    security = operator("정보안", OperatorRole.SECURITY_ADMIN);
    securitySession = sessionOf(security);
    owner = staff(OfficeMembershipRole.OWNER);
  }

  private long intake(String channel, String contact, int dueInDays) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO legal_guest_privacy_intake (office_id, link_kind, link_id, request_type,"
            + " requester_role, contact_channel, contact_encrypted, request_note, received_at,"
            + " internal_due_at) VALUES (?, 'APPROVER_PROPERTY', 1, 'ACCESS', 'SELF', ?, ?,"
            + " '조건 열람 요청', now() - interval '1 day', now() + (? * interval '1 day'))"
            + " RETURNING id",
        Long.class,
        owner.office().getId(),
        channel,
        textCipher.encrypt(contact),
        dueInDays);
  }

  private long integration() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO integration_config (environment, provider, function_code,"
            + " secret_reference_id, masked_connection, retention_policy, contract_status,"
            + " consent_status) VALUES ('TEST', 'mail-provider', 'MAIL', 'secret-ref', 'm***',"
            + " '30일', 'PENDING', 'NOT_REQUIRED') RETURNING id",
        Long.class);
  }

  private long recordAuthority(Cookie session) throws Exception {
    String response =
        post(
                AUTHORITY,
                "{\"receivedAt\":\"2026-11-01T09:00:00+09:00\",\"agencyType\":\"INVESTIGATION\","
                    + "\"agencyName\":\"기관 예시\",\"documentReference\":\"문서-1\","
                    + "\"legalBasisNote\":\"검토 대상 법령\",\"targetOfficeId\":"
                    + owner.office().getId()
                    + ",\"requestedItems\":\"가입 정보\"}",
                session)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("PENDING"))
            .andExpect(jsonPath("$.closeNote").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.officeNoticeStatus").value("NOT_REVIEWED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(response.replaceAll("(?s).*\"requestId\":(\\d+).*", "$1"));
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_없으면_403이다() throws Exception {
    Cookie support = sessionOf(operator("김지원", OperatorRole.SUPPORT));
    for (String path :
        new String[] {INTAKES, AUTHORITY, "/api/ops/integrations/1/contract-copies"}) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(support)), ErrorCode.FORBIDDEN);
    }
    for (String path :
        new String[] {
          INTAKES + "/1/resolve",
          AUTHORITY,
          AUTHORITY + "/1/close",
          "/api/ops/integrations/1/contract-copies"
        }) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, "{}", support), ErrorCode.FORBIDDEN);
    }
    assertError(mockMvc.perform(get(INTAKES).cookie(securitySession)), ErrorCode.FORBIDDEN);
    assertError(post(AUTHORITY, "{}", securitySession), ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get(INTAKES).cookie(memberSession(owner))), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 접수_큐는_연락처를_가리고_상태와_사무소로_거른다() throws Exception {
    long email = intake("EMAIL", "requester@example.com", 2);
    long phone = intake("PHONE", "010-1234-5678", 1);
    jdbcTemplate.update(
        "UPDATE legal_guest_privacy_intake SET status = 'CLOSED', closed_at = now(),"
            + " closed_reason = 'DUPLICATE' WHERE id = ?",
        email);

    mockMvc
        .perform(get(INTAKES).cookie(privacySession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].intakeId").value(phone))
        .andExpect(jsonPath("$.items[0].maskedContact").value("010-****-5678"))
        .andExpect(jsonPath("$.items[0].officeName").value(owner.office().getName()))
        .andExpect(jsonPath("$.items[1].maskedContact").value("re***@example.com"));
    mockMvc
        .perform(get(INTAKES).param("status", "RECEIVED").cookie(privacySession))
        .andExpect(jsonPath("$.totalElements").value(1));
    mockMvc
        .perform(get(INTAKES).param("officeId", "999999").cookie(privacySession))
        .andExpect(jsonPath("$.totalElements").value(0));
    assertFieldError(
        mockMvc.perform(get(INTAKES).param("status", "NOPE").cookie(privacySession)),
        "status",
        "NOT_ALLOWED");
    String body =
        mockMvc
            .perform(get(INTAKES).cookie(privacySession))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("requester@example.com").doesNotContain("1234-5678");
  }

  @Test
  void 확인된_접수를_전환하면_사무소_처리_권리_요청을_만들고_다시_전환하면_409다() throws Exception {
    long id = intake("EMAIL", "requester@example.com", 2);
    String path = INTAKES + "/" + id + "/resolve";
    assertFieldError(
        post(path, "{\"verificationResult\":\"FAILED\",\"action\":\"CONVERT\"}", privacySession),
        "action",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            path,
            "{\"verificationResult\":\"VERIFIED\",\"action\":\"CONVERT\",\"requestScope\":\"조건\"}",
            privacySession),
        "legalBasis",
        "REQUIRED");
    assertError(
        post(
            INTAKES + "/999999/resolve",
            "{\"verificationResult\":\"VERIFIED\",\"action\":\"CLOSE\",\"closeReason\":\"DUPLICATE\"}",
            privacySession),
        ErrorCode.RESOURCE_NOT_FOUND);

    String convert =
        "{\"verificationResult\":\"VERIFIED\",\"action\":\"CONVERT\",\"legalBasis\":\"검토 중\","
            + "\"requestScope\":\"승인자 링크로 제출한 방문 조건 열람\"}";
    post(path, convert, privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONVERTED"))
        .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
        .andExpect(jsonPath("$.privacyRequestId").isNumber());
    assertThat(
            count(
                "SELECT count(*) FROM privacy_request p JOIN legal_guest_privacy_intake g ON"
                    + " g.privacy_request_id = p.id WHERE g.id = ? AND p.requester_user_id IS NULL"
                    + " AND p.handler_type = 'OFFICE' AND p.identity_status = 'VERIFIED' AND"
                    + " p.office_id = ? AND p.request_type = 'ACCESS' AND p.received_at ="
                    + " g.received_at AND p.legal_due_at = g.received_at + interval '10 days' AND"
                    + " p.internal_due_at = g.received_at + interval '7 days'",
                id,
                owner.office().getId()))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM privacy_request_event e JOIN legal_guest_privacy_intake g ON"
                    + " g.privacy_request_id = e.privacy_request_id WHERE g.id = ? AND"
                    + " e.actor_operator_id = ?",
                id,
                privacy.id()))
        .isEqualTo(1);
    assertThat(auditCount("GUEST_PRIVACY_INTAKE_CONVERTED")).isEqualTo(1);
    assertError(post(path, convert, privacySession), ErrorCode.GUEST_INTAKE_ALREADY_CONVERTED);
  }

  @Test
  void 확인_실패는_사유와_함께_종결하고_기한_순서가_틀리면_400이다() throws Exception {
    long id = intake("PHONE", "010-1234-5678", 2);
    String path = INTAKES + "/" + id + "/resolve";
    assertFieldError(
        post(path, "{\"verificationResult\":\"FAILED\",\"action\":\"CLOSE\"}", privacySession),
        "closeReason",
        "REQUIRED");
    assertFieldError(
        post(
            path,
            "{\"verificationResult\":\"VERIFIED\",\"action\":\"CONVERT\",\"legalBasis\":\"검토 중\","
                + "\"requestScope\":\"범위\",\"internalDueAt\":\"2999-01-01T00:00:00+09:00\"}",
            privacySession),
        "legalDueAt",
        "OUT_OF_RANGE");
    post(
            path,
            "{\"verificationResult\":\"FAILED\",\"action\":\"CLOSE\",\"closeReason\":\"UNVERIFIABLE\"}",
            privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.closedReason").value("UNVERIFIABLE"))
        .andExpect(jsonPath("$.privacyRequestId").value(Matchers.nullValue()));
    assertThat(count("SELECT count(*) FROM privacy_request")).isZero();
    assertError(
        post(
            path,
            "{\"verificationResult\":\"FAILED\",\"action\":\"CLOSE\",\"closeReason\":\"DUPLICATE\"}",
            privacySession),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 자료_요청은_기록하고_목록에_기록자와_삭제_예정일을_보인다() throws Exception {
    assertError(
        post(
            AUTHORITY,
            "{\"receivedAt\":\"2026-11-01T09:00:00+09:00\",\"agencyType\":\"COURT\",\"agencyName\":"
                + "\"법원\",\"legalBasisNote\":\"근거\",\"targetUserId\":999999,\"requestedItems\":"
                + "\"자료\"}",
            privacySession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        post(
            AUTHORITY,
            "{\"receivedAt\":\"2999-11-01T09:00:00+09:00\",\"agencyType\":\"COURT\",\"agencyName\":"
                + "\"법원\",\"legalBasisNote\":\"근거\",\"requestedItems\":\"자료\"}",
            privacySession),
        "receivedAt",
        "OUT_OF_RANGE");
    assertError(
        post(AUTHORITY, "{\"agencyType\":\"NOPE\"}", privacySession), ErrorCode.INVALID_REQUEST);

    long id = recordAuthority(privacySession);
    post(
            AUTHORITY + "/" + id + "/close",
            "{\"decision\":\"REFUSED\",\"officeNoticeStatus\":\"NOT_ALLOWED\",\"closeNote\":\"범위"
                + " 불일치\"}",
            privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("REFUSED"))
        .andExpect(jsonPath("$.closeNote").value("범위 불일치"))
        .andExpect(jsonPath("$.approvedByOperatorId").value(Matchers.nullValue()))
        .andExpect(
            jsonPath("$.retentionUntil")
                .value(TimeRules.displayDate(Instant.now()).plusDays(1825).toString()));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT close_note FROM legal_authority_request WHERE id = ?", String.class, id))
        .isEqualTo("범위 불일치");
    long withoutNote = recordAuthority(privacySession);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT close_note FROM legal_authority_request WHERE id = ?",
                String.class,
                withoutNote))
        .isNull();

    mockMvc
        .perform(get(AUTHORITY).cookie(securitySession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].recordedByOperatorId").value(privacy.id()))
        .andExpect(jsonPath("$.items[0].recordedByOperatorName").value("최개인"));
    mockMvc
        .perform(get(AUTHORITY).param("open", "true").cookie(privacySession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].retentionUntil").value(Matchers.nullValue()));
    mockMvc
        .perform(get(AUTHORITY).param("decision", "REFUSED").cookie(privacySession))
        .andExpect(jsonPath("$.items[0].requestId").value(id));
    assertThat(auditCount("AUTHORITY_REQUEST_CLOSED")).isEqualTo(1);
    assertError(
        post(
            AUTHORITY + "/" + id + "/close",
            "{\"decision\":\"REFUSED\",\"officeNoticeStatus\":\"DEFERRED\"}",
            privacySession),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 제공_결정은_기록자와_다른_보안관리자만_한다() throws Exception {
    Operator both = operator("한겸직", OperatorRole.PRIVACY, OperatorRole.SECURITY_ADMIN);
    Cookie bothSession = sessionOf(both);
    long id = recordAuthority(bothSession);
    String path = AUTHORITY + "/" + id + "/close";
    String provide =
        "{\"decision\":\"PROVIDED\",\"providedItems\":\"가입 정보\",\"providedAt\":"
            + "\"2026-11-02T10:00:00+09:00\",\"officeNoticeStatus\":\"DEFERRED\"}";
    assertError(post(path, provide, bothSession), ErrorCode.SELF_APPROVAL_FORBIDDEN);
    assertError(post(path, provide, privacySession), ErrorCode.FORBIDDEN);
    assertFieldError(
        post(
            path,
            "{\"decision\":\"PROVIDED\",\"officeNoticeStatus\":\"DEFERRED\"}",
            securitySession),
        "providedItems",
        "REQUIRED");
    assertFieldError(
        post(
            path,
            "{\"decision\":\"REFUSED\",\"providedItems\":\"자료\",\"officeNoticeStatus\":"
                + "\"DEFERRED\"}",
            securitySession),
        "providedItems",
        "NOT_ALLOWED");
    assertError(
        post(AUTHORITY + "/999999/close", provide, securitySession), ErrorCode.RESOURCE_NOT_FOUND);

    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", security.id());
    assertError(post(path, provide, securitySession), ErrorCode.FORBIDDEN);
    jdbcTemplate.update(
        "INSERT INTO operator_role_assignment (operator_account_id, role) VALUES (?,"
            + " 'SECURITY_ADMIN')",
        security.id());

    post(path, provide, securitySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("PROVIDED"))
        .andExpect(jsonPath("$.approvedByOperatorId").value(security.id()))
        .andExpect(jsonPath("$.recordedByOperatorId").value(both.id()))
        .andExpect(jsonPath("$.providedItems").value("가입 정보"))
        .andExpect(jsonPath("$.closedAt").exists());
  }

  @Test
  void 계약서_사본은_해시와_위치를_등록하고_같은_버전은_409다() throws Exception {
    long integrationId = integration();
    String path = "/api/ops/integrations/" + integrationId + "/contract-copies";
    String body =
        "{\"documentKind\":\"SERVICE_CONTRACT\",\"versionLabel\":\"v1\",\"signedOn\":\"2026-11-20\","
            + "\"validUntil\":\"2027-11-19\",\"storageKey\":\"private-object-key\","
            + "\"contentSha256\":\""
            + SHA.toUpperCase()
            + "\"}";
    assertFieldError(
        post(path, body.replace("2027-11-19", "2026-01-01"), privacySession),
        "validUntil",
        "OUT_OF_RANGE");
    assertError(
        post(path, body.replace(SHA.toUpperCase(), "xyz"), privacySession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post("/api/ops/integrations/999999/contract-copies", body, privacySession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        mockMvc.perform(get("/api/ops/integrations/999999/contract-copies").cookie(privacySession)),
        ErrorCode.RESOURCE_NOT_FOUND);

    post(path, body, privacySession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.integrationId").value(integrationId))
        .andExpect(jsonPath("$.contentSha256").value(SHA))
        .andExpect(jsonPath("$.uploadedByOperatorId").value(privacy.id()))
        .andExpect(jsonPath("$.retentionUntil").value("2032-11-17"))
        .andExpect(jsonPath("$.storageKey").doesNotExist());
    assertError(post(path, body, privacySession), ErrorCode.DOCUMENT_VERSION_CONFLICT);
    post(
            path,
            body.replace("\"v1\"", "\"v2\"").replace(",\"validUntil\":\"2027-11-19\"", ""),
            privacySession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.retentionUntil").value(Matchers.nullValue()));

    mockMvc
        .perform(get(path).cookie(privacySession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].storageKey").doesNotExist());
    assertThat(auditCount("PROVIDER_CONTRACT_COPY_REGISTERED")).isEqualTo(2);
  }
}
