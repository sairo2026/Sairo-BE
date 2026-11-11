package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsIntegrationTestBase;
import com.sairo.be.support.SupportIntegrationTestBase;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class OpsImportSupportIntegrationTest extends SupportIntegrationTestBase {

  private static final String BASE = "/api/ops/import-support-requests";
  private static final String QUOTE = "{\"quoteAmount\":220000,\"scopeNote\":\"원본 1종\"}";

  private record Op(long id, Cookie cookie) {}

  private Op op(String name, OperatorRole... roles) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (?, ?, 'ACTIVE', true, 'x') RETURNING id",
            Long.class,
            "kakao-" + name + "-" + System.nanoTime(),
            name);
    for (OperatorRole role : roles) {
      jdbcTemplate.update(
          "INSERT INTO operator_role_assignment (operator_account_id, role) VALUES (?, ?)",
          id,
          role.name());
    }
    String sessionId =
        sessions.createOperatorSession(
            new OperatorPrincipal(id, name, Arrays.asList(roles)), Instant.now(), Instant.now());
    Cookie cookie =
        new Cookie(
            OpsIntegrationTestBase.OPS_COOKIE_NAME,
            Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
    return new Op(id, cookie);
  }

  private ResultActions get(String url, Cookie cookie) throws Exception {
    return mockMvc.perform(authedGet(url, cookie));
  }

  private ResultActions post(String url, String body, Cookie cookie, Object version)
      throws Exception {
    return mockMvc.perform(postWithVersion(url, body, cookie, version));
  }

  private ResultActions putMapping(long requestId, String body, Cookie cookie, Object version)
      throws Exception {
    MockHttpServletRequestBuilder builder =
        put(BASE + "/" + requestId + "/mapping")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (version != null) {
      builder.header("If-Match", String.valueOf(version));
    }
    return mockMvc.perform(withSession(builder, cookie));
  }

  private static JsonNode json(ResultActions result) throws Exception {
    return AuthFlowSupport.json(result.andReturn());
  }

  private long audits(String action, long requestId) {
    Long value =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ops_audit_log WHERE action_type = ? AND target_id = ?",
            Long.class,
            action,
            requestId);
    return value == null ? 0 : value;
  }

  @Test
  void 이전_지원_운영_API는_세션과_이전담당_역할이_없으면_막힌다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie member = cookieOf(owner);
    Op support = op("고객지원", OperatorRole.SUPPORT, OperatorRole.PRIVACY);
    List<String> posts =
        List.of(
            "/1/quote", "/1/reject", "/1/start", "/1/submit-review", "/1/execute", "/1/deliver");

    assertError(get(BASE, null), ErrorCode.UNAUTHENTICATED);
    assertError(get(BASE + "/1", null), ErrorCode.UNAUTHENTICATED);
    assertError(get(BASE, member), ErrorCode.UNAUTHENTICATED);
    assertError(putMapping(1, CUSTOMER_MAPPING, null, 1), ErrorCode.UNAUTHENTICATED);
    for (String path : posts) {
      assertError(post(BASE + path, QUOTE, null, 1), ErrorCode.UNAUTHENTICATED);
      assertError(post(BASE + path, QUOTE, support.cookie(), 1), ErrorCode.FORBIDDEN);
    }
    assertError(get(BASE, support.cookie()), ErrorCode.FORBIDDEN);
    assertError(get(BASE + "/1", support.cookie()), ErrorCode.FORBIDDEN);
    assertError(putMapping(1, CUSTOMER_MAPPING, support.cookie(), 1), ErrorCode.FORBIDDEN);
  }

  @Test
  void 견적과_거절은_버전_사유_상태를_검사하고_감사_로그를_남긴다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    Op migration = op("이전담당", OperatorRole.MIGRATION);
    long requestId = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long version = supportVersion(requestId);

    assertError(
        post(BASE + "/999999/quote", QUOTE, migration.cookie(), 1), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(BASE + "/" + requestId + "/quote", QUOTE, migration.cookie(), null),
        ErrorCode.INVALID_REQUEST);
    assertError(
        post(BASE + "/" + requestId + "/quote", QUOTE, migration.cookie(), version + 5),
        ErrorCode.VERSION_CONFLICT);
    assertFieldError(
        post(BASE + "/" + requestId + "/quote", "{\"quoteAmount\":0}", migration.cookie(), version),
        "quoteAmount",
        "OUT_OF_RANGE");

    JsonNode quoted =
        json(
            post(BASE + "/" + requestId + "/quote", QUOTE, migration.cookie(), version)
                .andExpect(status().isCreated()));
    assertThat(quoted.get("status").asString()).isEqualTo("QUOTED");
    assertThat(quoted.get("quoteAmount").longValue()).isEqualTo(220_000L);
    assertThat(audits("IMPORT_SUPPORT_QUOTED", requestId)).isEqualTo(1);

    long quotedVersion = supportVersion(requestId);
    assertFieldError(
        post(
            BASE + "/" + requestId + "/reject",
            "{\"reason\":\" \"}",
            migration.cookie(),
            quotedVersion),
        "reason",
        "REQUIRED");
    JsonNode rejected =
        json(
            post(
                    BASE + "/" + requestId + "/reject",
                    "{\"reason\":\"표준 범위를 넘는 자료다\"}",
                    migration.cookie(),
                    quotedVersion)
                .andExpect(status().isOk()));
    assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
    assertThat(rejected.get("rejectedAt").isNull()).isFalse();
    assertThat(audits("IMPORT_SUPPORT_REJECTED", requestId)).isEqualTo(1);
    assertError(
        post(
            BASE + "/" + requestId + "/reject",
            "{\"reason\":\"다시\"}",
            migration.cookie(),
            supportVersion(requestId)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(
            BASE + "/" + requestId + "/quote",
            QUOTE,
            migration.cookie(),
            supportVersion(requestId)),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 처리_시작부터_납품까지_운영_API로_이어지고_상세는_원문_값을_담지_않는다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    Op migration = op("이전담당", OperatorRole.MIGRATION);
    Op other = op("다른담당", OperatorRole.MIGRATION);
    long batchId = uploadCustomers(cookie, "C-1,홍길동,,,", "C-2,김철수,,,");
    long requestId = toPaid(cookie, batchId);

    JsonNode started =
        json(
            post(
                    BASE + "/" + requestId + "/start",
                    null,
                    migration.cookie(),
                    supportVersion(requestId))
                .andExpect(status().isOk()));
    assertThat(started.get("status").asString()).isEqualTo("PROCESSING");
    assertThat(started.get("assignedOperatorId").longValue()).isEqualTo(migration.id());
    assertError(
        post(BASE + "/" + requestId + "/start", null, other.cookie(), supportVersion(requestId)),
        ErrorCode.INVALID_TRANSITION);

    assertError(
        post(
            BASE + "/" + requestId + "/submit-review",
            null,
            migration.cookie(),
            supportVersion(requestId)),
        ErrorCode.MAPPING_REQUIRED);
    assertError(
        putMapping(requestId, CUSTOMER_MAPPING, other.cookie(), supportVersion(requestId)),
        ErrorCode.FORBIDDEN);
    String wrongType = CUSTOMER_MAPPING.replaceFirst("\\{", "{\"targetType\":\"PROPERTY\",");
    assertFieldError(
        putMapping(requestId, wrongType, migration.cookie(), supportVersion(requestId)),
        "targetType",
        "INVALID_VALUE");
    String sameType = CUSTOMER_MAPPING.replaceFirst("\\{", "{\"targetType\":\"CUSTOMER\",");
    JsonNode mapped =
        json(
            putMapping(requestId, sameType, migration.cookie(), supportVersion(requestId))
                .andExpect(status().isOk()));
    assertThat(mapped.get("status").asString()).isEqualTo("PROCESSING");
    assertThat(mapped.get("mappingVersionNo").intValue()).isGreaterThanOrEqualTo(1);

    JsonNode processing =
        json(get(BASE + "/" + requestId, migration.cookie()).andExpect(status().isOk()));
    assertThat(processing.get("currentMapping").get("confirmedByType").asString())
        .isEqualTo("OPERATOR");
    assertThat(processing.get("sourceColumns").toString()).contains("성명");
    assertThat(
            processing
                .get("mappingHistory")
                .get(processing.get("mappingHistory").size() - 1)
                .get("confirmedByName")
                .asString())
        .isEqualTo("이전담당");
    assertThat(processing.get("allowedActions").toString())
        .contains("EDIT_MAPPING", "SUBMIT_REVIEW");
    assertThat(processing.toString()).doesNotContain("홍길동", "김철수");
    assertThat(audits("IMPORT_SUPPORT_VIEWED", requestId)).isEqualTo(1);
    JsonNode otherView = json(get(BASE + "/" + requestId, other.cookie()));
    assertThat(otherView.get("allowedActions")).isEmpty();

    JsonNode submitted =
        json(
            post(
                    BASE + "/" + requestId + "/submit-review",
                    null,
                    migration.cookie(),
                    supportVersion(requestId))
                .andExpect(status().isOk()));
    assertThat(submitted.get("status").asString()).isEqualTo("REVIEW");
    assertThat(submitted.get("submittedMappingVersionNo").intValue())
        .isEqualTo(mapped.get("mappingVersionNo").intValue());
    assertError(
        putMapping(requestId, CUSTOMER_MAPPING, migration.cookie(), supportVersion(requestId)),
        ErrorCode.INVALID_TRANSITION);

    approve(cookie, requestId);
    assertError(
        post(
            BASE + "/" + requestId + "/deliver",
            null,
            migration.cookie(),
            supportVersion(requestId)),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(BASE + "/" + requestId + "/execute", null, other.cookie(), supportVersion(requestId)),
        ErrorCode.FORBIDDEN);
    JsonNode executed =
        json(
            post(
                    BASE + "/" + requestId + "/execute",
                    null,
                    migration.cookie(),
                    supportVersion(requestId))
                .andExpect(status().isAccepted()));
    assertThat(executed.get("status").asString()).isEqualTo("APPROVED");
    assertThat(executed.get("importBatchId").longValue()).isEqualTo(batchId);
    assertThat(executed.get("approvedMappingVersionNo").intValue())
        .isEqualTo(mapped.get("mappingVersionNo").intValue());
    assertThat(batchStatus(batchId)).isEqualTo("COMPLETED");
    assertError(
        post(
            BASE + "/" + requestId + "/execute",
            null,
            migration.cookie(),
            supportVersion(requestId)),
        ErrorCode.INVALID_TRANSITION);

    JsonNode delivered =
        json(
            post(
                    BASE + "/" + requestId + "/deliver",
                    null,
                    migration.cookie(),
                    supportVersion(requestId))
                .andExpect(status().isOk()));
    assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
    assertThat(delivered.get("deliveredAt").isNull()).isFalse();
    for (String action :
        List.of(
            "IMPORT_SUPPORT_STARTED",
            "IMPORT_SUPPORT_MAPPING_SAVED",
            "IMPORT_SUPPORT_REVIEW_SUBMITTED",
            "IMPORT_SUPPORT_EXECUTED",
            "IMPORT_SUPPORT_DELIVERED")) {
      assertThat(audits(action, requestId)).as(action).isEqualTo(1);
    }
  }

  @Test
  void 목록은_상태_담당_사무소로_거르고_잘못된_조건은_400이다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    var otherOwner = newStaff(OfficeMembershipRole.OWNER);
    Cookie otherCookie = cookieOf(otherOwner);
    Op migration = op("이전담당", OperatorRole.MIGRATION);
    long requested = supportOn(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    long processing = toPaid(otherCookie, uploadCustomers(otherCookie, "C-9,박영희,,,"));
    post(BASE + "/" + processing + "/start", null, migration.cookie(), supportVersion(processing))
        .andExpect(status().isOk());

    JsonNode all = json(get(BASE, migration.cookie()).andExpect(status().isOk()));
    assertThat(all.get("totalElements").longValue()).isEqualTo(2);
    JsonNode mine = json(get(BASE + "?assignedToMe=true", migration.cookie()));
    assertThat(mine.get("totalElements").longValue()).isEqualTo(1);
    assertThat(mine.get("items").get(0).get("requestId").longValue()).isEqualTo(processing);
    assertThat(mine.get("items").get(0).get("assignedOperatorName").asString()).isEqualTo("이전담당");
    JsonNode byStatus = json(get(BASE + "?status=REQUESTED", migration.cookie()));
    assertThat(byStatus.get("items").get(0).get("requestId").longValue()).isEqualTo(requested);
    JsonNode byOffice =
        json(get(BASE + "?officeId=" + otherOwner.office().getId(), migration.cookie()));
    assertThat(byOffice.get("totalElements").longValue()).isEqualTo(1);
    assertFieldError(get(BASE + "?status=DONE", migration.cookie()), "status", "NOT_ALLOWED");
    assertError(get(BASE + "/999999", migration.cookie()), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 이전담당_역할이_DB에서_회수되면_처리할_수_없다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);
    Cookie cookie = cookieOf(owner);
    Op migration = op("이전담당", OperatorRole.MIGRATION);
    long requestId = toPaid(cookie, uploadCustomers(cookie, "C-1,홍길동,,,"));
    jdbcTemplate.update(
        "DELETE FROM operator_role_assignment WHERE operator_account_id = ?", migration.id());

    assertError(
        post(
            BASE + "/" + requestId + "/start", null, migration.cookie(), supportVersion(requestId)),
        ErrorCode.FORBIDDEN);
    assertThat(supportStatus(requestId)).isEqualTo("PAID");
  }
}
