package com.sairo.be.scenario;

import static com.sairo.be.support.AuthFlowSupport.json;
import static com.sairo.be.support.AuthFlowSupport.jsonPost;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.migration.service.ExportGenerationService;
import com.sairo.be.domain.office.entity.Office;
import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.VisitOutcomeTestBase;
import com.sairo.be.support.fixture.OfficeBuilder;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * BE-11 인계 흐름: 사무소 종료 → 대표 탈퇴 → 반환권한 재인증 → 내보내기 → 반환권한으로 남은 방문 정리를 실제 엔드포인트로 잇는다. 사무소·대표 계정과 확정 방문
 * 두 건, 운영자 계정·세션만 시험 데이터로 만든다.
 */
@IntegrationTest
class LaunchScenarioClosureIntegrationTest extends VisitOutcomeTestBase {

  private static final String EMAIL = "closing-owner@example.com";

  @Autowired private ExportGenerationService exportGeneration;

  private Cookie privacyOperator() {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (?, '최개인', 'ACTIVE', true, 'x') RETURNING id",
            Long.class,
            "scenario-privacy-" + System.nanoTime());
    jdbcTemplate.update(
        "INSERT INTO operator_role_assignment (operator_account_id, role) VALUES (?, 'PRIVACY')",
        id);
    String sessionId =
        sessions.createOperatorSession(
            new OperatorPrincipal(id, "최개인", List.of(OperatorRole.PRIVACY)),
            Instant.now(),
            Instant.now());
    return new Cookie(
        "__Host-sairo_ops_session",
        Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
  }

  /** 이메일 로그인 뒤 실제 비밀번호 재인증까지 마친 회원 세션이다. */
  private Cookie loginWithStepUp() throws Exception {
    Cookie cookie = login(EMAIL, PASSWORD);
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/step-up/password", "{\"password\":" + quote(PASSWORD) + "}", cookie))
        .andExpect(status().isOk());
    return cookie;
  }

  private long confirmedVisit(TestData.Staff owner, String from, String to) throws Exception {
    RequestFixture f = requestFixture(owner, true, true);
    long requestId =
        json(mockMvc
                .perform(
                    jsonPostAs(
                        requestUrl(f.coordinationId()),
                        requestBody(f.candidateId(), visitDay(), from, to),
                        f.cookie()))
                .andExpect(status().isCreated())
                .andReturn())
            .get("requestId")
            .longValue();
    String token = issueApproverToken(f.cookie(), f.propertyId(), f.approvalId());
    selectNow(token, requestId).andExpect(status().isOk());
    assertThat(coordinationStatus(f.coordinationId())).isEqualTo("CONFIRMED");
    return f.coordinationId();
  }

  private long approvalOf(long coordinationId) {
    return jdbcTemplate.queryForObject(
        "SELECT r.approval_id FROM visit_request r WHERE r.coordination_id = ? AND r.status ="
            + " 'CONFIRMED'",
        Long.class,
        coordinationId);
  }

  @Test
  void 사무소를_종료한_대표가_탈퇴한_뒤에도_반환권한으로_재인증해_자료를_받고_남은_방문을_정리한다() throws Exception {
    AppUser user = saveEmailUser(EMAIL, "010-3333-4444");
    Office office = officeRepository.saveAndFlush(OfficeBuilder.anOffice().build());
    TestData.Staff owner = testData().approvedStaffIn(office, user, OfficeMembershipRole.OWNER);
    long completedCoordination = confirmedVisit(owner, "10:00", "10:30");
    long revokedCoordination = confirmedVisit(owner, "13:00", "13:30");
    long revokedApproval = approvalOf(revokedCoordination);

    // 1. 유일한 대표는 바로 탈퇴할 수 없고 사무소를 종료해야 한다.
    assertError(
        mockMvc.perform(AuthFlowSupport.withCsrf(delete("/api/me").cookie(loginWithStepUp()))),
        ErrorCode.LAST_OWNER);
    JsonNode closed =
        json(
            mockMvc
                .perform(
                    jsonPost(
                        "/api/offices/current/close",
                        "{\"confirmed\":true,\"archiveChoice\":\"DECLINE\"}",
                        loginWithStepUp()))
                .andExpect(status().isOk())
                .andReturn());
    long grantId = closed.get("returnGrantId").longValue();
    assertThat(closed.get("returnExpiresAt").isNull()).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM office_membership WHERE id = ?",
                String.class,
                owner.membership().getId()))
        .isEqualTo("REVOKED");

    // 2. 종료한 대표가 탈퇴한다. 반환권한은 회수되지 않는다.
    mockMvc
        .perform(AuthFlowSupport.withCsrf(delete("/api/me").cookie(loginWithStepUp())))
        .andExpect(status().isNoContent());
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT account_status FROM app_user WHERE id = ?", String.class, user.getId()))
        .isEqualTo("WITHDRAWN");
    assertThat(
            count("SELECT count(*) FROM return_grant WHERE id = ? AND revoked_at IS NULL", grantId))
        .isEqualTo(1);

    // 3. 반환권한 재인증(이메일·비밀번호)으로 반환 전용 세션을 받는다.
    Cookie grant =
        AuthFlowSupport.sessionCookie(
            mockMvc
                .perform(
                    jsonPost(
                        "/api/grant-auth/return",
                        "{\"grantId\":"
                            + grantId
                            + ",\"authMethod\":\"EMAIL\",\"credential\":"
                            + quote(PASSWORD)
                            + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grantType").value("RETURN"))
                .andReturn());
    mockMvc
        .perform(get("/api/return-grants/current").cookie(grant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grantId").value(grantId))
        .andExpect(jsonPath("$.revoked").value(false));
    assertError(mockMvc.perform(get("/api/offices/current").cookie(grant)), ErrorCode.FORBIDDEN);

    // 4. 전체 내보내기를 요청하고 생성 배치가 파일을 만들면 내려받을 수 있다.
    long exportId =
        json(mockMvc
                .perform(jsonPost("/api/return-grants/" + grantId + "/export", "", grant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn())
            .get("exportId")
            .longValue();
    assertThat(exportGeneration.generatePending()).isGreaterThanOrEqualTo(1);
    mockMvc
        .perform(get("/api/return-grants/" + grantId + "/exports/" + exportId).cookie(grant))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.downloadUrl").isString());

    // 5. 반환권한으로 남은 방문을 정리한다: 지난 방문 결과 사후 기록, 출입승인 철회 접수, 조율 취소.
    long item = scheduledItem(completedCoordination);
    mockMvc
        .perform(
            jsonPatchAs(
                "/api/return-grants/" + grantId + "/visit-schedule-items/" + item + "/result",
                "{\"result\":\"COMPLETED\",\"actualVisitedAt\":\""
                    + minutesAgo(40)
                    + "\",\"effectiveAt\":\""
                    + minutesAgo(30)
                    + "\"}",
                grant,
                coordinationVersion(completedCoordination)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("VISIT_COMPLETED"));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT recorded_by_membership_id FROM visit_schedule_item_result_history WHERE"
                    + " schedule_item_id = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                item))
        .isEqualTo(owner.membership().getId());
    postVersioned(
            "/api/return-grants/"
                + grantId
                + "/visit-coordinations/"
                + revokedCoordination
                + "/access-revocations",
            "{\"approvalId\":"
                + revokedApproval
                + ",\"revokedAt\":\""
                + minutesAgo(5)
                + "\",\"reason\":\"승인자의 철회 요청\"}",
            grant,
            coordinationVersion(revokedCoordination))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revokedByUserId").value(user.getId()))
        .andExpect(jsonPath("$.status").value("RESCHEDULE_REQUIRED"));
    postVersioned(
            "/api/return-grants/"
                + grantId
                + "/visit-coordinations/"
                + revokedCoordination
                + "/cancel",
            "{\"reason\":\"사무소 종료로 인한 취소\"}",
            grant,
            coordinationVersion(revokedCoordination))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertThat(coordinationStatus(revokedCoordination)).isEqualTo("CANCELLED");

    // 6. 탈퇴 회원 파기 큐는 반환권한 유효 기간 동안 보류로 보인다.
    long queueId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM deletion_queue WHERE target_type = 'MEMBER' AND target_id = ?",
            Long.class,
            user.getId());
    mockMvc
        .perform(get("/api/ops/deletion-queue/" + queueId + "/preview").cookie(privacyOperator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdReasons[0]").value("반환권한 유효 기간 중"));
  }
}
