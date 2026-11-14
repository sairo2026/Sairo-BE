package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.json;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class OpsPrivacyOpsIntegrationTest extends OpsConsoleTestBase {

  private ResultActions read(String path, Cookie cookie) throws Exception {
    return mockMvc.perform(cookie == null ? get(path) : get(path).cookie(cookie));
  }

  /** 권리 요청 처리 요청에는 저장된 현재 version을 If-Match로 붙인다. 없는 요청에는 1을 보낸다. */
  @Override
  protected ResultActions post(String path, String body, Cookie cookie) throws Exception {
    if (cookie == null || !path.endsWith("/process")) {
      return super.post(path, body, cookie);
    }
    String id = path.replaceAll(".*/privacy-requests/(\\d+)/process", "$1");
    List<Long> versions =
        jdbcTemplate.queryForList(
            "SELECT version FROM privacy_request WHERE id = ?", Long.class, Long.valueOf(id));
    return mockMvc.perform(
        jsonPost(path, body, cookie)
            .header("If-Match", versions.isEmpty() ? "1" : String.valueOf(versions.get(0))));
  }

  private long insertRequest(long userId, String identity, String status, String dueSql) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO privacy_request (requester_user_id, request_type, identity_status,"
            + " legal_basis, legal_due_at, internal_due_at, request_scope, status) VALUES (?,"
            + " 'ACCESS', ?, '개인정보 보호법 제35조', now() + interval '"
            + dueSql
            + "', now() + interval '"
            + dueSql
            + "' - interval '3 days', '본인 계정 정보 열람', ?) RETURNING id",
        Long.class,
        userId,
        identity,
        status);
  }

  private String process(String action, String note, String evidence) {
    StringBuilder body = new StringBuilder("{\"action\":\"" + action + "\"");
    if (note != null) {
      body.append(",\"note\":\"").append(note).append('"');
    }
    if (evidence != null) {
      body.append(",\"evidenceReference\":\"").append(evidence).append('"');
    }
    return body.append('}').toString();
  }

  private long insertReturnGrant(long officeId, long userId) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO return_grant (office_id, granted_to_user_id, granted_at, expires_at) VALUES"
            + " (?, ?, now(), now() + interval '30 days') RETURNING id",
        Long.class,
        officeId,
        userId);
  }

  private long insertArchiveGrant(long officeId, long userId) {
    return jdbcTemplate.queryForObject(
        "INSERT INTO archive_grant (office_id, granted_to_user_id, contract_reference,"
            + " expires_at) VALUES (?, ?, 'ARC-1', now() + interval '1 year') RETURNING id",
        Long.class,
        officeId,
        userId);
  }

  private static String transfer(long userId, String extra) {
    return "{\"newGranteeUserId\":"
        + userId
        + ",\"identityEvidenceReference\":\"IDV-1\",\"registrationRelationReference\":\"REG-1\""
        + extra
        + ",\"reason\":\"대표 변경\"}";
  }

  @Test
  void 개인정보_요청과_권한_API는_세션과_개인정보담당_역할이_없으면_막힌다() throws Exception {
    Cookie member = memberSession(staff(OfficeMembershipRole.OWNER));
    Cookie support = sessionOf(operator("고객지원", OperatorRole.SUPPORT, OperatorRole.MIGRATION));
    List<String> reads =
        List.of(
            "/api/ops/privacy-requests",
            "/api/ops/privacy-requests/1",
            "/api/ops/return-grants",
            "/api/ops/archive-grants");
    List<String> writes =
        List.of(
            "/api/ops/privacy-requests/1/process",
            "/api/ops/return-grants/1/transfer",
            "/api/ops/archive-grants/1/transfer");
    for (String path : reads) {
      assertError(read(path, null), ErrorCode.UNAUTHENTICATED);
      assertError(read(path, member), ErrorCode.UNAUTHENTICATED);
      assertError(read(path, support), ErrorCode.FORBIDDEN);
    }
    for (String path : writes) {
      assertError(post(path, process("FORWARD", null, null), null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, process("FORWARD", null, null), support), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 권리_요청_큐는_필터와_기한_상태를_보이고_요청자_식별_값을_담지_않는다() throws Exception {
    TestData.Staff staff = staff(OfficeMembershipRole.OWNER);
    var privacy = operator("개인정보담당", OperatorRole.PRIVACY);
    Cookie cookie = sessionOf(privacy);
    long open = insertRequest(staff.user().getId(), "PENDING", "RECEIVED", "5 days");
    long late = insertRequest(staff.user().getId(), "VERIFIED", "REVIEWING", "1 days");
    long done = insertRequest(staff.user().getId(), "VERIFIED", "COMPLETED", "5 days");
    jdbcTemplate.update(
        "UPDATE privacy_request SET internal_due_at = now() - interval '1 hour', legal_due_at ="
            + " now() + interval '1 day' WHERE id = ?",
        late);

    JsonNode all =
        json(read("/api/ops/privacy-requests", cookie).andExpect(status().isOk()).andReturn());
    assertThat(all.get("totalElements").longValue()).isEqualTo(3);
    assertThat(all.get("items").get(0).get("requestId").longValue()).isEqualTo(late);
    assertThat(all.get("items").get(0).get("dueState").asString()).isEqualTo("APPROACHING");
    assertThat(all.get("items").get(1).get("dueState").asString()).isEqualTo("ON_TRACK");
    assertThat(all.get("items").get(2).get("requestId").longValue()).isEqualTo(done);
    assertThat(all.get("items").get(2).get("dueState").isNull()).isTrue();
    assertThat(all.toString()).doesNotContain(staff.user().getName());
    JsonNode received =
        json(
            read("/api/ops/privacy-requests?status=RECEIVED&requestType=ACCESS", cookie)
                .andReturn());
    assertThat(received.get("items").get(0).get("requestId").longValue()).isEqualTo(open);
    assertFieldError(
        read("/api/ops/privacy-requests?status=DONE", cookie), "status", "NOT_ALLOWED");
    assertFieldError(
        read("/api/ops/privacy-requests?requestType=X", cookie), "requestType", "NOT_ALLOWED");
    assertError(read("/api/ops/privacy-requests/999999", cookie), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 권리_요청_처리는_행위별_상태_규칙과_본인확인을_검사하고_이력을_남긴다() throws Exception {
    TestData.Staff staff = staff(OfficeMembershipRole.OWNER);
    var privacy = operator("개인정보담당", OperatorRole.PRIVACY);
    Cookie cookie = sessionOf(privacy);
    long requestId = insertRequest(staff.user().getId(), "PENDING", "RECEIVED", "10 days");
    String path = "/api/ops/privacy-requests/" + requestId + "/process";

    assertFieldError(post(path, "{\"action\":\"HOLD\"}", cookie), "action", "INVALID_FORMAT");
    assertFieldError(post(path, process("REJECT", null, null), cookie), "note", "REQUIRED");
    assertFieldError(
        post(path, process("COMPLETE", null, null), cookie), "evidenceReference", "REQUIRED");
    assertError(
        post(path, process("APPROVE", null, null), cookie), ErrorCode.IDENTITY_NOT_VERIFIED);
    assertError(post(path, process("EXECUTE", null, null), cookie), ErrorCode.INVALID_TRANSITION);
    assertError(
        post("/api/ops/privacy-requests/999999/process", process("FORWARD", null, null), cookie),
        ErrorCode.RESOURCE_NOT_FOUND);

    JsonNode supplement =
        json(
            post(path, process("REQUEST_SUPPLEMENT", "신분 확인 자료를 보내 주세요", null), cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(supplement.get("status").asString()).isEqualTo("WAITING_FOR_INFO");
    assertError(post(path, process("APPROVE", null, null), cookie), ErrorCode.INVALID_TRANSITION);
    post(path, process("REQUEST_COOPERATION", "보완 자료 접수", null), cookie).andExpect(status().isOk());
    JsonNode approved =
        json(
            post(
                    path,
                    "{\"action\":\"APPROVE\",\"identityStatus\":\"VERIFIED\",\"note\":\"열람 범위 확인\"}",
                    cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
    post(path, process("EXECUTE", null, null), cookie).andExpect(status().isOk());
    post(path, process("COMPLETE", "열람 자료 제공", "PRV-1"), cookie).andExpect(status().isOk());
    assertError(post(path, process("FORWARD", null, null), cookie), ErrorCode.INVALID_TRANSITION);

    JsonNode detail =
        json(
            read("/api/ops/privacy-requests/" + requestId, cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(detail.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(detail.get("identityStatus").asString()).isEqualTo("VERIFIED");
    assertThat(detail.get("decisionReason").asString()).isEqualTo("열람 범위 확인");
    assertThat(detail.get("completionEvidence").asString()).isEqualTo("PRV-1");
    assertThat(detail.get("assignedOperatorId").longValue()).isEqualTo(privacy.id());
    assertThat(detail.get("assignedOperatorName").asString()).isEqualTo("개인정보담당");
    assertThat(detail.get("dueState").isNull()).isTrue();
    assertThat(detail.get("events")).hasSize(5);
    assertThat(detail.get("events").get(0).get("eventType").asString())
        .isEqualTo("REQUEST_SUPPLEMENT");
    assertThat(detail.get("events").get(0).get("actorOperatorName").asString()).isEqualTo("개인정보담당");
    assertThat(detail.get("version").longValue()).isEqualTo(6);
    assertThat(detail.get("allowedActions")).isEmpty();
    assertThat(auditCount("PRIVACY_REQUEST_PROCESSED")).isEqualTo(5);

    long rejected = insertRequest(staff.user().getId(), "PENDING", "RECEIVED", "10 days");
    JsonNode reject =
        json(
            post(
                    "/api/ops/privacy-requests/" + rejected + "/process",
                    process("REJECT", "요청 범위가 불명확합니다", null),
                    cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(reject.get("status").asString()).isEqualTo("REJECTED");
  }

  @Test
  void 반환권한자_변경은_재인증_후_이전_권한을_회수하고_30일_새_권한을_발급한다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    TestData.Staff heir = staff(OfficeMembershipRole.OWNER);
    var privacy = operator("개인정보담당", OperatorRole.PRIVACY);
    Cookie cookie = sessionOf(privacy);
    long officeId = owner.office().getId();
    long grantId = insertReturnGrant(officeId, owner.user().getId());
    String path = "/api/ops/return-grants/" + grantId + "/transfer";
    String body = transfer(heir.user().getId(), "");

    assertError(post(path, body, cookie), ErrorCode.STEP_UP_REQUIRED);
    stepUp(cookie);
    assertError(
        post("/api/ops/return-grants/999999/transfer", body, cookie), ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        post(path, transfer(owner.user().getId(), ""), cookie), "newGranteeUserId", "NOT_ALLOWED");
    assertFieldError(
        post(path, "{\"newGranteeUserId\":" + heir.user().getId() + ",\"reason\":\"변경\"}", cookie),
        "identityEvidenceReference",
        "REQUIRED");

    JsonNode result = json(post(path, body, cookie).andExpect(status().isOk()).andReturn());
    long newId = result.get("newGrantId").longValue();
    assertThat(result.get("previousGrantId").longValue()).isEqualTo(grantId);
    assertThat(result.get("newGranteeUserId").longValue()).isEqualTo(heir.user().getId());
    assertThat(
            count(
                "SELECT count(*) FROM return_grant WHERE id = ? AND replaced_by_grant_id = ? AND"
                    + " revoked_at IS NOT NULL",
                grantId,
                newId))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM return_grant WHERE id = ? AND identity_evidence_reference ="
                    + " 'IDV-1' AND expires_at = granted_at + interval '30 days'",
                newId))
        .isEqualTo(1);
    assertThat(auditCount("RETURN_GRANT_TRANSFERRED")).isEqualTo(1);
    assertError(
        post(path, transfer(heir.user().getId(), ""), cookie), ErrorCode.INVALID_TRANSITION);

    JsonNode list =
        json(
            read("/api/ops/return-grants?activeOnly=true&officeId=" + officeId, cookie)
                .andReturn());
    assertThat(list.get("totalElements").longValue()).isEqualTo(1);
    assertThat(list.get("items").get(0).get("grantId").longValue()).isEqualTo(newId);
    JsonNode history =
        json(read("/api/ops/return-grants?officeId=" + officeId, cookie).andReturn());
    assertThat(history.get("totalElements").longValue()).isEqualTo(2);
  }

  @Test
  void 보관권한자_변경은_계약_참조를_받고_만료를_이어받는다() throws Exception {
    TestData.Staff owner = staff(OfficeMembershipRole.OWNER);
    TestData.Staff heir = staff(OfficeMembershipRole.OWNER);
    var privacy = operator("개인정보담당", OperatorRole.PRIVACY);
    Cookie cookie = sessionOf(privacy);
    long grantId = insertArchiveGrant(owner.office().getId(), owner.user().getId());
    String path = "/api/ops/archive-grants/" + grantId + "/transfer";
    stepUp(cookie);

    assertFieldError(
        post(path, transfer(heir.user().getId(), ""), cookie), "contractReference", "REQUIRED");
    JsonNode result =
        json(
            post(path, transfer(heir.user().getId(), ",\"contractReference\":\"ARC-2\""), cookie)
                .andExpect(status().isOk())
                .andReturn());
    long newId = result.get("newGrantId").longValue();
    assertThat(result.get("expiresAt").isNull()).isFalse();
    assertThat(
            count(
                "SELECT count(*) FROM archive_grant n JOIN archive_grant o ON o.replaced_by_grant_id"
                    + " = n.id WHERE n.id = ? AND n.contract_reference = 'ARC-2' AND n.expires_at ="
                    + " o.expires_at AND o.terminated_at IS NOT NULL",
                newId))
        .isEqualTo(1);
    JsonNode list = json(read("/api/ops/archive-grants?activeOnly=true", cookie).andReturn());
    assertThat(list.get("items").get(0).get("contractReference").asString()).isEqualTo("ARC-2");
    assertError(
        post(path, transfer(heir.user().getId(), ",\"contractReference\":\"ARC-3\""), cookie),
        ErrorCode.INVALID_TRANSITION);

    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", privacy.id());
    long other = insertArchiveGrant(owner.office().getId(), owner.user().getId());
    assertError(
        post(
            "/api/ops/archive-grants/" + other + "/transfer",
            transfer(heir.user().getId(), ",\"contractReference\":\"ARC-4\""),
            cookie),
        ErrorCode.FORBIDDEN);
  }

  @Test
  void 본인확인_결과를_함께_보내면_대기_중인_회원_요청을_승인하거나_제한할_수_있다() throws Exception {
    TestData.Staff staff = staff(OfficeMembershipRole.OWNER);
    Cookie cookie = sessionOf(operator("개인정보담당", OperatorRole.PRIVACY));
    long restricted = insertRequest(staff.user().getId(), "PENDING", "RECEIVED", "10 days");
    String path = "/api/ops/privacy-requests/" + restricted + "/process";
    String restrict = "{\"action\":\"RESTRICT\",\"identityStatus\":\"%s\",\"note\":\"일부 범위만 제공\"}";

    assertError(post(path, restrict.formatted("UNKNOWN"), cookie), ErrorCode.INVALID_REQUEST);
    assertError(
        post(path, process("RESTRICT", "일부 범위만 제공", null), cookie),
        ErrorCode.IDENTITY_NOT_VERIFIED);
    assertError(post(path, restrict.formatted("FAILED"), cookie), ErrorCode.IDENTITY_NOT_VERIFIED);
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT identity_status, status FROM privacy_request WHERE id = ?", restricted))
        .containsEntry("identity_status", "PENDING")
        .containsEntry("status", "RECEIVED");

    JsonNode result =
        json(
            post(path, restrict.formatted("VERIFIED"), cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(result.get("status").asString()).isEqualTo("PARTIALLY_RESTRICTED");
    JsonNode detail =
        json(
            read("/api/ops/privacy-requests/" + restricted, cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(detail.get("identityStatus").asString()).isEqualTo("VERIFIED");
    assertThat(detail.get("decisionReason").asString()).isEqualTo("일부 범위만 제공");

    long approved = insertRequest(staff.user().getId(), "PENDING", "RECEIVED", "10 days");
    JsonNode approval =
        json(
            post(
                    "/api/ops/privacy-requests/" + approved + "/process",
                    "{\"action\":\"APPROVE\",\"identityStatus\":\"VERIFIED\"}",
                    cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(approval.get("status").asString()).isEqualTo("APPROVED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT identity_status FROM privacy_request WHERE id = ?", String.class, approved))
        .isEqualTo("VERIFIED");
  }
}
