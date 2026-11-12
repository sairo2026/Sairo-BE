package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.json;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class OpsDeletionQueueIntegrationTest extends OpsConsoleTestBase {

  private static final String BASE = "/api/ops/deletion-queue";
  private static final String VERIFY_ALL =
      "{\"storageResult\":\"SUCCESS\",\"searchIndexResult\":\"SUCCESS\",\"fileResult\":\"SUCCESS\","
          + "\"temporaryExportResult\":\"SUCCESS\",\"evidence\":\"삭제 로그 1\"}";

  @Autowired private ObjectStorage objectStorage;

  private ResultActions read(String path, Cookie cookie) throws Exception {
    return mockMvc.perform(cookie == null ? get(path) : get(path).cookie(cookie));
  }

  /** 탈퇴한 시험 회원과 그 회원의 임시 내보내기 파일, 예정 시각이 지난 파기 큐 행을 만든다. */
  private long withdrawnMemberQueue(TestData.Staff staff, String exportKey) {
    long userId = staff.user().getId();
    jdbcTemplate.update(
        "UPDATE app_user SET account_status = 'WITHDRAWN', withdrawn_at = now() WHERE id = ?",
        userId);
    if (exportKey != null) {
      objectStorage.put(exportKey, new byte[] {1, 2, 3}, "application/zip");
      jdbcTemplate.update(
          "INSERT INTO export_job (office_id, created_by_membership_id, scope, status,"
              + " file_object_key, file_expires_at) VALUES (?, ?, 'FULL', 'COMPLETED', ?, now() +"
              + " interval '1 day')",
          staff.office().getId(),
          staff.membership().getId(),
          exportKey);
    }
    return jdbcTemplate.queryForObject(
        "INSERT INTO deletion_queue (target_type, target_id, reason, scheduled_at) VALUES"
            + " ('MEMBER', ?, '회원 탈퇴', now() - interval '1 minute') RETURNING id",
        Long.class,
        userId);
  }

  private static String exclude(long approver) {
    OffsetDateTime hold = OffsetDateTime.now().plusMonths(3);
    return "{\"exclusionReason\":\"분쟁 진행 중\",\"holdExpiresAt\":\""
        + hold
        + "\",\"exclusionReviewedAt\":\""
        + hold.minusMonths(1)
        + "\",\"approvedByOperatorId\":"
        + approver
        + "}";
  }

  @Test
  void 파기_큐_API는_세션과_개인정보담당_역할이_없으면_막힌다() throws Exception {
    Cookie member = memberSession(staff(OfficeMembershipRole.OWNER));
    Cookie support = sessionOf(operator("보안관리자", OperatorRole.SECURITY_ADMIN, OperatorRole.OPS));
    for (String path : List.of(BASE, BASE + "/1", BASE + "/1/preview")) {
      assertError(read(path, null), ErrorCode.UNAUTHENTICATED);
      assertError(read(path, member), ErrorCode.UNAUTHENTICATED);
      assertError(read(path, support), ErrorCode.FORBIDDEN);
    }
    for (String path : List.of("/1/exclude", "/1/execute", "/1/verify", "/1/cancel")) {
      assertError(post(BASE + path, "{\"reason\":\"x\"}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(BASE + path, "{\"reason\":\"x\"}", support), ErrorCode.FORBIDDEN);
    }
  }

  @Test
  void 파기는_미리보기_재인증_실행_다른_운영자_검증_순으로_끝나고_실행자는_검증할_수_없다() throws Exception {
    TestData.Staff staff = staff(OfficeMembershipRole.OWNER);
    var executor = operator("실행담당", OperatorRole.PRIVACY);
    var verifier = operator("검증담당", OperatorRole.PRIVACY);
    Cookie cookie = sessionOf(executor);
    Cookie verifierCookie = sessionOf(verifier);
    String exportKey = "exports/test-" + System.nanoTime() + ".zip";
    long queueId = withdrawnMemberQueue(staff, exportKey);
    long userId = staff.user().getId();

    JsonNode preview =
        json(
            read(BASE + "/" + queueId + "/preview", cookie).andExpect(status().isOk()).andReturn());
    assertThat(preview.get("targetCount").longValue()).isEqualTo(2);
    assertThat(preview.get("storageTargets").get(0).get("targetId").longValue()).isEqualTo(userId);
    assertThat(preview.get("temporaryExportTargets").get(0).get("objectKey").asString())
        .isEqualTo(exportKey);
    assertThat(preview.get("holdReasons")).isEmpty();
    assertThat(count("SELECT target_count FROM deletion_queue WHERE id = ?", queueId)).isEqualTo(2);

    assertError(post(BASE + "/" + queueId + "/execute", "", cookie), ErrorCode.STEP_UP_REQUIRED);
    assertError(
        post(BASE + "/" + queueId + "/verify", VERIFY_ALL, verifierCookie),
        ErrorCode.INVALID_TRANSITION);
    stepUp(cookie);
    JsonNode executed =
        json(
            post(BASE + "/" + queueId + "/execute", "", cookie)
                .andExpect(status().isAccepted())
                .andReturn());
    assertThat(executed.get("result").isNull()).isTrue();
    assertThat(executed.get("storageResult").asString()).isEqualTo("SUCCESS");
    assertThat(executed.get("temporaryExportResult").asString()).isEqualTo("SUCCESS");
    assertThat(
            count(
                "SELECT count(*) FROM app_user WHERE id = ? AND email IS NULL AND phone IS NULL AND"
                    + " name = '탈퇴한 구성원' AND kakao_provider_key LIKE 'deleted-%'",
                userId))
        .isEqualTo(1);
    assertThat(objectStorage.find(exportKey)).isEmpty();
    assertThat(count("SELECT count(*) FROM export_job WHERE file_object_key = ?", exportKey))
        .isZero();
    assertError(post(BASE + "/" + queueId + "/execute", "", cookie), ErrorCode.INVALID_TRANSITION);
    assertError(
        post(BASE + "/" + queueId + "/cancel", "{\"reason\":\"늦은 취소\"}", cookie),
        ErrorCode.INVALID_TRANSITION);

    assertError(
        post(BASE + "/" + queueId + "/verify", VERIFY_ALL, cookie),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    assertError(
        post(
            BASE + "/" + queueId + "/verify",
            "{\"storageResult\":\"SUCCESS\",\"evidence\":\"로그\"}",
            verifierCookie),
        ErrorCode.VERIFICATION_INCOMPLETE);
    assertFieldError(
        post(
            BASE + "/" + queueId + "/verify",
            VERIFY_ALL.replace("\"fileResult\":\"SUCCESS\"", "\"fileResult\":\"DONE\""),
            verifierCookie),
        "fileResult",
        "INVALID_FORMAT");
    JsonNode verified =
        json(
            post(BASE + "/" + queueId + "/verify", VERIFY_ALL, verifierCookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(verified.get("result").asString()).isEqualTo("SUCCESS");
    assertThat(verified.get("verifiedByOperatorId").longValue()).isEqualTo(verifier.id());
    assertThat(verified.get("verifiedAt").isNull()).isFalse();
    assertError(
        post(BASE + "/" + queueId + "/verify", VERIFY_ALL, verifierCookie),
        ErrorCode.INVALID_TRANSITION);

    JsonNode one = json(read(BASE + "/" + queueId, cookie).andExpect(status().isOk()).andReturn());
    assertThat(one.get("result").asString()).isEqualTo("SUCCESS");
    assertThat(one.get("executedByOperatorId").longValue()).isEqualTo(executor.id());
    JsonNode filtered = json(read(BASE + "?queueId=" + queueId, cookie).andReturn());
    assertThat(filtered.get("totalElements").longValue()).isEqualTo(1);
    JsonNode done = json(read(BASE + "?state=SUCCESS", cookie).andReturn());
    assertThat(done.get("totalElements").longValue()).isEqualTo(1);
    for (String action :
        List.of("DELETION_QUEUE_PREVIEWED", "DELETION_QUEUE_EXECUTED", "DELETION_QUEUE_VERIFIED")) {
      assertThat(auditCount(action)).as(action).isEqualTo(1);
    }
  }

  @Test
  void 검증에서_실패가_있으면_FAILED로_남고_다시_실행하면_재시도_횟수가_오른다() throws Exception {
    TestData.Staff staff = staff(OfficeMembershipRole.OWNER);
    var executor = operator("실행담당", OperatorRole.PRIVACY);
    var verifier = operator("검증담당", OperatorRole.PRIVACY);
    Cookie cookie = sessionOf(executor);
    long queueId = withdrawnMemberQueue(staff, null);
    stepUp(cookie);
    post(BASE + "/" + queueId + "/execute", "", cookie).andExpect(status().isAccepted());

    JsonNode failed =
        json(
            post(
                    BASE + "/" + queueId + "/verify",
                    VERIFY_ALL.replace(
                        "\"searchIndexResult\":\"SUCCESS\"", "\"searchIndexResult\":\"FAILED\""),
                    sessionOf(verifier))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(failed.get("result").asString()).isEqualTo("FAILED");
    assertThat(failed.get("verifiedAt").isNull()).isTrue();
    JsonNode retried =
        json(
            post(BASE + "/" + queueId + "/execute", "", cookie)
                .andExpect(status().isAccepted())
                .andReturn());
    assertThat(retried.get("retryCount").intValue()).isEqualTo(1);
    assertThat(json(read(BASE + "/" + queueId, cookie).andReturn()).get("result").isNull())
        .isTrue();
  }

  @Test
  void 보존_예외는_다른_승인자가_필요하고_취소는_대기_건만_된다() throws Exception {
    var privacy = operator("개인정보담당", OperatorRole.PRIVACY);
    var approver = operator("승인담당", OperatorRole.PRIVACY);
    var outsider = operator("운영담당", OperatorRole.OPS);
    Cookie cookie = sessionOf(privacy);
    long excludedId = withdrawnMemberQueue(staff(OfficeMembershipRole.OWNER), null);
    long cancelledId = withdrawnMemberQueue(staff(OfficeMembershipRole.OWNER), null);

    assertError(
        post(BASE + "/" + excludedId + "/exclude", exclude(privacy.id()), cookie),
        ErrorCode.SELF_APPROVAL_FORBIDDEN);
    assertFieldError(
        post(BASE + "/" + excludedId + "/exclude", exclude(outsider.id()), cookie),
        "approvedByOperatorId",
        "NOT_ALLOWED");
    assertFieldError(
        post(
            BASE + "/" + excludedId + "/exclude",
            "{\"exclusionReason\":\"분쟁\",\"approvedByOperatorId\":" + approver.id() + "}",
            cookie),
        "holdExpiresAt",
        "REQUIRED");
    JsonNode excluded =
        json(
            post(BASE + "/" + excludedId + "/exclude", exclude(approver.id()), cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(excluded.get("result").asString()).isEqualTo("EXCLUDED");
    assertThat(excluded.get("exclusionReason").asString()).isEqualTo("분쟁 진행 중");
    stepUp(cookie);
    assertError(
        post(BASE + "/" + excludedId + "/execute", "", cookie), ErrorCode.INVALID_TRANSITION);
    JsonNode preview = json(read(BASE + "/" + excludedId + "/preview", cookie).andReturn());
    assertThat(preview.get("excludedCount").longValue())
        .isEqualTo(preview.get("targetCount").longValue());
    assertThat(preview.get("holdReasons").toString()).contains("분쟁 진행 중");

    assertFieldError(
        post(BASE + "/" + cancelledId + "/cancel", "{\"reason\":\" \"}", cookie),
        "reason",
        "REQUIRED");
    JsonNode cancelled =
        json(
            post(BASE + "/" + cancelledId + "/cancel", "{\"reason\":\"분쟁 접수로 보류\"}", cookie)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(cancelled.get("cancelledByOperatorId").longValue()).isEqualTo(privacy.id());
    assertError(
        post(BASE + "/" + cancelledId + "/cancel", "{\"reason\":\"다시\"}", cookie),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(BASE + "/" + cancelledId + "/execute", "", cookie), ErrorCode.INVALID_TRANSITION);
    JsonNode item = json(read(BASE + "/" + cancelledId, cookie).andReturn());
    assertThat(item.get("cancelledAt").isNull()).isFalse();
    assertThat(item.get("executedAt").isNull()).isTrue();
    assertThat(item.get("result").isNull()).isTrue();
    JsonNode cancelledList = json(read(BASE + "?state=CANCELLED", cookie).andReturn());
    assertThat(cancelledList.get("totalElements").longValue()).isEqualTo(1);
    assertFieldError(read(BASE + "?state=DONE", cookie), "state", "NOT_ALLOWED");
    assertError(read(BASE + "/999999", cookie), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 탈퇴하지_않은_회원이나_예정_전_건은_실행하지_않는다() throws Exception {
    TestData.Staff staff = staff(OfficeMembershipRole.OWNER);
    var privacy = operator("개인정보담당", OperatorRole.PRIVACY);
    Cookie cookie = sessionOf(privacy);
    stepUp(cookie);
    long queueId = withdrawnMemberQueue(staff, null);
    jdbcTemplate.update(
        "UPDATE app_user SET account_status = 'ACTIVE', withdrawn_at = NULL WHERE id = ?",
        staff.user().getId());
    JsonNode preview = json(read(BASE + "/" + queueId + "/preview", cookie).andReturn());
    assertThat(preview.get("holdReasons").toString()).contains("탈퇴하지 않은 회원");
    assertError(post(BASE + "/" + queueId + "/execute", "", cookie), ErrorCode.INVALID_TRANSITION);

    long future = withdrawnMemberQueue(staff(OfficeMembershipRole.OWNER), null);
    jdbcTemplate.update(
        "UPDATE deletion_queue SET scheduled_at = now() + interval '1 day' WHERE id = ?", future);
    assertError(post(BASE + "/" + future + "/execute", "", cookie), ErrorCode.INVALID_TRANSITION);
    assertThat(count("SELECT count(*) FROM app_user WHERE name = '탈퇴한 구성원'")).isZero();
  }
}
