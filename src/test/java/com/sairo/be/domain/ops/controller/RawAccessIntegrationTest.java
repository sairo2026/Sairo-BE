package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class RawAccessIntegrationTest extends OpsConsoleTestBase {

  private Operator requester;
  private Cookie requesterSession;
  private Operator privacy;
  private Cookie privacySession;
  private TestData.Staff owner;

  @BeforeEach
  void setUp() {
    requester = operator("박지원", OperatorRole.SUPPORT);
    requesterSession = sessionOf(requester);
    privacy = operator("최개인", OperatorRole.PRIVACY);
    privacySession = sessionOf(privacy);
    owner = staff(OfficeMembershipRole.OWNER);
  }

  private String body(String type, String extra) {
    return "{\"requestType\":\""
        + type
        + "\",\"targetType\":\"OFFICE\",\"targetId\":"
        + owner.office().getId()
        + ",\"purpose\":\"고객 문의 대응\",\"scope\":\"READ_ONLY\",\"requestedExpiresMinutes\":30,"
        + "\"basisType\":\"LEGAL_BASIS\",\"basisNote\":\"법적 근거 확인\""
        + extra
        + "}";
  }

  private long create(String type, Cookie session) throws Exception {
    String response =
        post("/api/ops/raw-access-requests", body(type, ""), session)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(response.replaceAll("(?s).*\"requestId\":(\\d+).*", "$1"));
  }

  private long approvedRequest(String type) throws Exception {
    long id = create(type, requesterSession);
    post("/api/ops/raw-access-requests/" + id + "/approve", "", privacySession)
        .andExpect(status().isOk());
    return id;
  }

  private long execute(long requestId) throws Exception {
    stepUp(requesterSession);
    String response =
        post("/api/ops/raw-access-requests/" + requestId + "/execute", "", requesterSession)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return Long.parseLong(response.replaceAll("(?s).*\"grantId\":(\\d+).*", "$1"));
  }

  private String requestStatus(long requestId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM ops_approval_request WHERE id = ?", String.class, requestId);
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_역할이_없으면_403이다() throws Exception {
    for (String path :
        new String[] {
          "/api/ops/raw-access-requests",
          "/api/ops/raw-access-requests/1",
          "/api/ops/raw-access-grants/1/file"
        }) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
    }
    for (String path :
        new String[] {
          "/api/ops/raw-access-requests",
          "/api/ops/raw-access-requests/1/approve",
          "/api/ops/raw-access-requests/1/reject",
          "/api/ops/raw-access-requests/1/execute",
          "/api/ops/raw-access-grants/1/revoke"
        }) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
    }
    for (String path :
        new String[] {
          "/api/ops/raw-access-requests/1/approve",
          "/api/ops/raw-access-requests/1/reject",
          "/api/ops/raw-access-grants/1/revoke"
        }) {
      assertError(post(path, "{}", requesterSession), ErrorCode.FORBIDDEN);
    }
    assertError(
        mockMvc.perform(get("/api/ops/raw-access-requests/1").cookie(memberSession(owner))),
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 요청_생성은_근거와_대상을_확인한다() throws Exception {
    String path = "/api/ops/raw-access-requests";
    assertError(
        post(
            path,
            body("RAW_VIEW", "").replace("LEGAL_BASIS", "REPRESENTATIVE_REQUEST"),
            requesterSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            path,
            body("RAW_VIEW", ",\"linkedInquiryId\":999999")
                .replace("LEGAL_BASIS", "REPRESENTATIVE_REQUEST"),
            requesterSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(path, body("RAW_VIEW", ",\"officeId\":999999"), requesterSession),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            path,
            body("RAW_VIEW", "")
                .replace("\"requestedExpiresMinutes\":30", "\"requestedExpiresMinutes\":61"),
            requesterSession),
        ErrorCode.INVALID_REQUEST);
    assertError(post(path, body("RAW_COPY", ""), requesterSession), ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            path,
            body("RAW_VIEW", "")
                .replace("\"targetId\":" + owner.office().getId(), "\"targetId\":999999"),
            requesterSession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(path, body("RAW_VIEW", ",\"beneficiaryOperatorId\":999999"), requesterSession),
        ErrorCode.RESOURCE_NOT_FOUND);

    long inquiry = insertInquiry(owner.office().getId(), owner.user().getId());
    post(
            path,
            body("RAW_DOWNLOAD", ",\"linkedInquiryId\":" + inquiry)
                .replace("LEGAL_BASIS", "REPRESENTATIVE_REQUEST"),
            requesterSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requestType").value("RAW_DOWNLOAD"))
        .andExpect(jsonPath("$.targetType").value("OFFICE"))
        .andExpect(jsonPath("$.targetId").value(owner.office().getId()))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.beneficiaryOperatorId").value(nullValue()));
    assertThat(
            count(
                "SELECT count(*) FROM ops_audit_log WHERE action_type = 'RAW_ACCESS_REQUESTED'"
                    + " AND target_type = 'OFFICE' AND target_id = ?",
                owner.office().getId()))
        .isEqualTo(1);
  }

  @Test
  void 목록과_상세는_요청자와_개인정보담당만_본다() throws Exception {
    long id = create("RAW_VIEW", requesterSession);
    mockMvc
        .perform(get("/api/ops/raw-access-requests").cookie(requesterSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].requestId").value(id))
        .andExpect(jsonPath("$.items[0].requestedByName").value("박지원"));
    assertError(
        mockMvc.perform(
            get("/api/ops/raw-access-requests?scope=APPROVABLE").cookie(requesterSession)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get("/api/ops/raw-access-requests?scope=ALL").cookie(requesterSession)),
        ErrorCode.INVALID_REQUEST);
    mockMvc
        .perform(get("/api/ops/raw-access-requests?scope=APPROVABLE").cookie(privacySession))
        .andExpect(jsonPath("$.totalElements").value(1));
    mockMvc
        .perform(get("/api/ops/raw-access-requests").cookie(privacySession))
        .andExpect(jsonPath("$.totalElements").value(0));

    mockMvc
        .perform(get("/api/ops/raw-access-requests/" + id).cookie(privacySession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.basisType").value("LEGAL_BASIS"))
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()))
        .andExpect(jsonPath("$.grant").value(nullValue()));
    Cookie other = sessionOf(operator("운영", OperatorRole.OPS));
    assertError(
        mockMvc.perform(get("/api/ops/raw-access-requests/" + id).cookie(other)),
        ErrorCode.FORBIDDEN);
    assertError(
        mockMvc.perform(get("/api/ops/raw-access-requests/999999").cookie(requesterSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 승인과_거절은_요청자_수혜자_본인이_할_수_없다() throws Exception {
    long own = create("RAW_VIEW", privacySession);
    assertError(
        post("/api/ops/raw-access-requests/" + own + "/approve", "", privacySession),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    assertError(
        post(
            "/api/ops/raw-access-requests/" + own + "/reject",
            "{\"reason\":\"사유\"}",
            privacySession),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    String beneficiaryBody = body("RAW_VIEW", ",\"beneficiaryOperatorId\":" + privacy.id());
    String created =
        post("/api/ops/raw-access-requests", beneficiaryBody, requesterSession)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long forPrivacy = Long.parseLong(created.replaceAll("(?s).*\"requestId\":(\\d+).*", "$1"));
    assertError(
        post("/api/ops/raw-access-requests/" + forPrivacy + "/approve", "", privacySession),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);

    long id = create("RAW_VIEW", requesterSession);
    assertError(
        post("/api/ops/raw-access-requests/" + id + "/reject", "{\"reason\":\"\"}", privacySession),
        ErrorCode.INVALID_REQUEST);
    post("/api/ops/raw-access-requests/" + id + "/reject", "{\"reason\":\"근거 부족\"}", privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectedAt").exists());
    assertError(
        post("/api/ops/raw-access-requests/" + id + "/approve", "", privacySession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/raw-access-requests/999999/approve", "", privacySession),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(auditCount("RAW_ACCESS_REJECTED")).isEqualTo(1);
  }

  @Test
  void 열람_승인은_수혜자가_재인증_뒤_한_번만_실행하고_파일은_받을_수_없다() throws Exception {
    long id = create("RAW_VIEW", requesterSession);
    post("/api/ops/raw-access-requests/" + id + "/approve", "", privacySession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.expiresAt").exists());
    assertThat(
            count(
                "SELECT count(*) FROM ops_approval_request WHERE id = ? AND expires_at ="
                    + " approved_at + interval '24 hours'",
                id))
        .isEqualTo(1);
    assertError(
        post("/api/ops/raw-access-requests/" + id + "/approve", "", privacySession),
        ErrorCode.INVALID_TRANSITION);

    String execute = "/api/ops/raw-access-requests/" + id + "/execute";
    stepUp(privacySession);
    assertError(post(execute, "", privacySession), ErrorCode.FORBIDDEN);
    assertError(post(execute, "", requesterSession), ErrorCode.STEP_UP_REQUIRED);
    stepUp(requesterSession);
    post(execute, "", requesterSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalRequestId").value(id))
        .andExpect(jsonPath("$.requestType").value("RAW_VIEW"))
        .andExpect(jsonPath("$.fileSize").value(nullValue()))
        .andExpect(jsonPath("$.downloadPath").value(nullValue()));
    assertThat(
            count(
                "SELECT count(*) FROM ops_raw_access_grant WHERE approval_request_id = ? AND"
                    + " expires_at = executed_at + interval '60 minutes'",
                id))
        .isEqualTo(1);
    assertError(post(execute, "", requesterSession), ErrorCode.INVALID_TRANSITION);
    long grantId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM ops_raw_access_grant WHERE approval_request_id = ?", Long.class, id);
    assertError(
        mockMvc.perform(
            get("/api/ops/raw-access-grants/" + grantId + "/file").cookie(requesterSession)),
        ErrorCode.DOWNLOAD_NOT_APPROVED);
    assertThat(auditCount("RAW_ACCESS_APPROVED")).isEqualTo(1);
    assertThat(auditCount("RAW_ACCESS_EXECUTED")).isEqualTo(1);
  }

  @Test
  void 다운로드_승인은_실행자에게_파일을_한_번만_준다() throws Exception {
    long id = approvedRequest("RAW_DOWNLOAD");
    stepUp(requesterSession);
    MvcResult executed =
        post("/api/ops/raw-access-requests/" + id + "/execute", "", requesterSession)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fileSize").isNumber())
            .andExpect(jsonPath("$.fileSha256").isString())
            .andReturn();
    String json = executed.getResponse().getContentAsString();
    long grantId = Long.parseLong(json.replaceAll("(?s).*\"grantId\":(\\d+).*", "$1"));
    String sha = json.replaceAll("(?s).*\"fileSha256\":\"([0-9a-f]{64})\".*", "$1");
    assertThat(json)
        .contains("\"downloadPath\":\"/api/ops/raw-access-grants/" + grantId + "/file\"");

    String file = "/api/ops/raw-access-grants/" + grantId + "/file";
    stepUp(privacySession);
    assertError(mockMvc.perform(get(file).cookie(privacySession)), ErrorCode.FORBIDDEN);
    expireStepUp(requesterSession);
    assertError(mockMvc.perform(get(file).cookie(requesterSession)), ErrorCode.STEP_UP_REQUIRED);
    stepUp(requesterSession);
    MvcResult downloaded =
        mockMvc
            .perform(get(file).cookie(requesterSession))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-SHA256", sha))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    String content = downloaded.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(content).contains(owner.office().getBusinessRegistrationNumber());
    assertError(
        mockMvc.perform(get(file).cookie(requesterSession)), ErrorCode.RAW_DOWNLOAD_ALREADY_USED);
    mockMvc
        .perform(get("/api/ops/raw-access-requests/" + id).cookie(requesterSession))
        .andExpect(jsonPath("$.grant.grantId").value(grantId))
        .andExpect(jsonPath("$.grant.fileSha256").value(sha))
        .andExpect(jsonPath("$.grant.downloadedAt").exists());
    assertThat(auditCount("RAW_ACCESS_DOWNLOADED")).isEqualTo(1);
  }

  @Test
  void 보안관리자는_실행_중인_접근을_회수하고_회수된_파일은_받을_수_없다() throws Exception {
    long grantId = execute(approvedRequest("RAW_DOWNLOAD"));
    Cookie admin = sessionOf(operator("김보안", OperatorRole.SECURITY_ADMIN));
    String revoke = "/api/ops/raw-access-grants/" + grantId + "/revoke";
    assertError(post(revoke, "{\"reason\":\"목적 외 사용 의심\"}", admin), ErrorCode.STEP_UP_REQUIRED);
    stepUp(admin);
    assertError(post(revoke, "{\"reason\":\"\"}", admin), ErrorCode.INVALID_REQUEST);
    assertError(
        post("/api/ops/raw-access-grants/999999/revoke", "{\"reason\":\"사유\"}", admin),
        ErrorCode.RESOURCE_NOT_FOUND);
    post(revoke, "{\"reason\":\"목적 외 사용 의심\"}", admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantId").value(grantId))
        .andExpect(jsonPath("$.revokedAt").exists());
    assertError(post(revoke, "{\"reason\":\"다시\"}", admin), ErrorCode.INVALID_TRANSITION);
    stepUp(requesterSession);
    assertError(
        mockMvc.perform(
            get("/api/ops/raw-access-grants/" + grantId + "/file").cookie(requesterSession)),
        ErrorCode.INVALID_TRANSITION);
    assertThat(auditCount("RAW_ACCESS_REVOKED")).isEqualTo(1);
  }

  @Test
  void 승인자_권한이_회수되거나_승인이_만료되면_실행은_409다() throws Exception {
    long revokedApprover = approvedRequest("RAW_VIEW");
    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ? AND role = 'PRIVACY'",
        privacy.id());
    stepUp(requesterSession);
    assertError(
        post("/api/ops/raw-access-requests/" + revokedApprover + "/execute", "", requesterSession),
        ErrorCode.APPROVAL_INVALIDATED);
    assertThat(requestStatus(revokedApprover)).isEqualTo("INVALIDATED");
    assertThat(count("SELECT count(*) FROM ops_raw_access_grant")).isZero();

    jdbcTemplate.update(
        "INSERT INTO operator_role_assignment (operator_account_id, role) VALUES (?, 'PRIVACY')",
        privacy.id());
    long expired = approvedRequest("RAW_VIEW");
    jdbcTemplate.update(
        "UPDATE ops_approval_request SET approved_at = now() - interval '25 hours', expires_at ="
            + " now() - interval '1 hour' WHERE id = ?",
        expired);
    mockMvc
        .perform(get("/api/ops/raw-access-requests?status=EXPIRED").cookie(requesterSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].requestId").value(expired));
    assertError(
        post("/api/ops/raw-access-requests/" + expired + "/execute", "", requesterSession),
        ErrorCode.APPROVAL_INVALIDATED);
    assertThat(requestStatus(expired)).isEqualTo("EXPIRED");
  }
}
