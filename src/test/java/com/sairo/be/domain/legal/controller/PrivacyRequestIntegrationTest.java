package com.sairo.be.domain.legal.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.DeskIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class PrivacyRequestIntegrationTest extends DeskIntegrationTestBase {

  private static String body(String type, String content) {
    return "{\"requestType\":\"" + type + "\",\"content\":\"" + content + "\"}";
  }

  private JsonNode create(Member member, String type) throws Exception {
    return AuthFlowSupport.json(
        postJson("/api/privacy-requests", body(type, "내 정보를 요청합니다"), member.cookie())
            .andExpect(status().isCreated())
            .andReturn());
  }

  @Test
  void 여섯_유형을_모두_접수하고_본인확인_대기와_기한을_기록한다() throws Exception {
    Member member = newMember("a@example.com");

    for (String type :
        List.of("ACCESS", "CORRECTION", "DELETION", "SUSPENSION", "WITHDRAWAL", "PORTABILITY")) {
      JsonNode created = create(member, type);

      assertThat(created.get("requestId").isNumber()).isTrue();
      assertThat(created.get("supportInquiryId").isNumber()).isTrue();
      assertThat(created.get("requestType").asString()).isEqualTo(type);
      assertThat(created.get("identityStatus").asString()).isEqualTo("PENDING");
      assertThat(created.get("status").asString()).isEqualTo("RECEIVED");
      OffsetDateTime received = OffsetDateTime.parse(created.get("receivedAt").asString());
      OffsetDateTime legal = OffsetDateTime.parse(created.get("legalDueAt").asString());
      OffsetDateTime internal = OffsetDateTime.parse(created.get("internalDueAt").asString());
      assertThat(Duration.between(received, legal)).isEqualTo(Duration.ofDays(10));
      assertThat(Duration.between(received, internal)).isEqualTo(Duration.ofDays(7));
    }
    assertThat(count("privacy_request")).isEqualTo(6);
    assertThat(count("privacy_request_event")).isEqualTo(6);
  }

  @Test
  void 접수는_PRIVACY_지원_문의와_같은_거래로_만들고_처리자는_사이로다() throws Exception {
    Member member = newMember("a@example.com");

    JsonNode created = create(member, "ACCESS");

    long inquiryId = created.get("supportInquiryId").longValue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT category || ':' || requester_user_id || ':' || content || ':' || status"
                    + " FROM support_inquiry WHERE id = ?",
                String.class,
                inquiryId))
        .isEqualTo("PRIVACY:" + member.id() + ":내 정보를 요청합니다:OPEN");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT i.response_due_at = r.legal_due_at FROM privacy_request r"
                    + " JOIN support_inquiry i ON i.id = r.support_inquiry_id",
                Boolean.class))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT requester_user_id || ':' || handler_type || ':' || identity_status || ':'"
                    + " || (office_id IS NULL) || ':' || (legal_basis <> '')"
                    + " FROM privacy_request",
                String.class))
        .isEqualTo(member.id() + ":SAIRO:PENDING:true:true");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT event_type || ':' || new_status || ':' || (previous_status IS NULL)"
                    + " FROM privacy_request_event",
                String.class))
        .isEqualTo("RECEIVED:RECEIVED:true");
  }

  @Test
  void 요청_범위를_주면_저장하고_주지_않으면_범위_미지정으로_둔다() throws Exception {
    Member member = newMember("a@example.com");

    postJson(
            "/api/privacy-requests",
            "{\"requestType\":\"CORRECTION\",\"content\":\"이름 정정\",\"requestScope\":\"가입 이름\"}",
            member.cookie())
        .andExpect(status().isCreated());
    create(member, "ACCESS");

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT request_scope FROM privacy_request WHERE request_type = 'CORRECTION'",
                String.class))
        .isEqualTo("가입 이름");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT request_scope FROM privacy_request WHERE request_type = 'ACCESS'",
                String.class))
        .contains("범위 미지정");
  }

  @Test
  void 입력이_잘못되면_400이고_문의와_요청_어느_쪽도_만들지_않는다() throws Exception {
    Member member = newMember("a@example.com");

    assertFieldError(
        postJson("/api/privacy-requests", "{\"content\":\"a\"}", member.cookie()),
        "requestType",
        "REQUIRED");
    assertFieldError(
        postJson("/api/privacy-requests", body("RESTORE", "a"), member.cookie()),
        "requestType",
        "NOT_ALLOWED");
    assertFieldError(
        postJson("/api/privacy-requests", body("ACCESS", " "), member.cookie()),
        "content",
        "REQUIRED");
    assertFieldError(
        postJson(
            "/api/privacy-requests",
            "{\"requestType\":\"ACCESS\",\"content\":\"a\",\"status\":\"COMPLETED\"}",
            member.cookie()),
        "status",
        "NOT_ALLOWED");
    assertThat(count("privacy_request")).isZero();
    assertThat(count("support_inquiry")).isZero();
  }

  @Test
  void 세션이_없으면_모든_권리_요청_엔드포인트가_401이다() throws Exception {
    assertError(
        postJson("/api/privacy-requests", body("ACCESS", "a"), null), ErrorCode.UNAUTHENTICATED);
    assertError(getAs("/api/privacy-requests/me", null), ErrorCode.UNAUTHENTICATED);
    assertError(getAs("/api/privacy-requests/1", null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 이력은_본인_요청만_접수_최신순으로_돌려준다() throws Exception {
    Member member = newMember("a@example.com");
    Member other = newMember("b@example.com");
    JsonNode first = create(member, "ACCESS");
    JsonNode second = create(member, "DELETION");
    create(other, "ACCESS");

    getAs("/api/privacy-requests/me", member.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].requestId").value(second.get("requestId").longValue()))
        .andExpect(jsonPath("$.items[0].requestType").value("DELETION"))
        .andExpect(jsonPath("$.items[0].status").value("RECEIVED"))
        .andExpect(jsonPath("$.items[0].identityStatus").value("PENDING"))
        .andExpect(jsonPath("$.items[0].legalDueAt").exists())
        .andExpect(jsonPath("$.items[0].completedAt").doesNotExist())
        .andExpect(jsonPath("$.items[0].decisionReason").doesNotExist())
        .andExpect(jsonPath("$.items[0].responsibleOfficeName").doesNotExist())
        .andExpect(jsonPath("$.items[0].internalDueAt").doesNotExist())
        .andExpect(jsonPath("$.items[1].requestId").value(first.get("requestId").longValue()));
  }

  @Test
  void 이력은_처리_상태로_거르고_허용되지_않는_값은_400이다() throws Exception {
    Member member = newMember("a@example.com");
    JsonNode first = create(member, "ACCESS");
    create(member, "DELETION");
    jdbcTemplate.update(
        "UPDATE privacy_request SET status = 'REVIEWING' WHERE id = ?",
        first.get("requestId").longValue());

    getAs("/api/privacy-requests/me?status=REVIEWING", member.cookie())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].requestId").value(first.get("requestId").longValue()));
    getAs("/api/privacy-requests/me?status=REVIEWING,RECEIVED", member.cookie())
        .andExpect(jsonPath("$.totalElements").value(2));
    assertFieldError(
        getAs("/api/privacy-requests/me?status=DONE", member.cookie()), "status", "NOT_ALLOWED");
    assertFieldError(
        getAs("/api/privacy-requests/me?size=10", member.cookie()), "size", "NOT_ALLOWED");
  }

  @Test
  void 이력은_페이지로_나눠_돌려준다() throws Exception {
    Member member = newMember("a@example.com");
    for (int i = 0; i < 3; i++) {
      create(member, "ACCESS");
    }
    jdbcTemplate.update(
        "UPDATE privacy_request SET received_at = received_at + id * interval '1 second'");

    getAs("/api/privacy-requests/me?size=25&page=1", member.cookie())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.totalElements").value(3));
  }

  @Test
  void 상세는_처리_상태와_본인확인과_기한과_처리_주체를_돌려준다() throws Exception {
    Member member = newMember("a@example.com");
    JsonNode created = create(member, "ACCESS");
    long requestId = created.get("requestId").longValue();

    getAs("/api/privacy-requests/" + requestId, member.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestId").value(requestId))
        .andExpect(jsonPath("$.requestType").value("ACCESS"))
        .andExpect(jsonPath("$.status").value("RECEIVED"))
        .andExpect(jsonPath("$.identityStatus").value("PENDING"))
        .andExpect(jsonPath("$.receivedAt").exists())
        .andExpect(jsonPath("$.legalDueAt").exists())
        .andExpect(jsonPath("$.internalDueAt").exists())
        .andExpect(jsonPath("$.completedAt").doesNotExist())
        .andExpect(jsonPath("$.decisionReason").doesNotExist())
        .andExpect(jsonPath("$.responsibleOfficeName").doesNotExist())
        .andExpect(jsonPath("$.handlerType").value("SAIRO"))
        .andExpect(jsonPath("$.supplementRequest").doesNotExist())
        .andExpect(jsonPath("$.legalBasis").doesNotExist())
        .andExpect(jsonPath("$.assignedOperatorId").doesNotExist());
  }

  @Test
  void 거절과_사무소_전달_결정은_결정_사유와_책임_사무소_이름으로_보여_준다() throws Exception {
    Member member = newMember("a@example.com");
    var owner = newStaff(OfficeMembershipRole.OWNER);
    long requestId = create(member, "DELETION").get("requestId").longValue();
    jdbcTemplate.update(
        "UPDATE privacy_request SET status = 'REJECTED', identity_status = 'VERIFIED',"
            + " decision_reason = '법령에 따라 보존해야 하는 항목입니다', office_id = ?,"
            + " handler_type = 'OFFICE', completed_at = now() WHERE id = ?",
        owner.office().getId(),
        requestId);

    getAs("/api/privacy-requests/" + requestId, member.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.identityStatus").value("VERIFIED"))
        .andExpect(jsonPath("$.decisionReason").value("법령에 따라 보존해야 하는 항목입니다"))
        .andExpect(jsonPath("$.responsibleOfficeName").value(owner.office().getName()))
        .andExpect(jsonPath("$.handlerType").value("OFFICE"))
        .andExpect(jsonPath("$.completedAt").exists());
  }

  @Test
  void 보완_대기일_때만_운영자가_요청한_보완_항목_안내를_보여_준다() throws Exception {
    Member member = newMember("a@example.com");
    long requestId = create(member, "ACCESS").get("requestId").longValue();
    jdbcTemplate.update(
        "INSERT INTO privacy_request_event (privacy_request_id, event_type, previous_status,"
            + " new_status, action_detail) VALUES (?, 'REQUEST_SUPPLEMENT', 'RECEIVED',"
            + " 'WAITING_FOR_INFO', '가입 이메일과 휴대폰 뒤 네 자리를 알려 주세요')",
        requestId);
    jdbcTemplate.update(
        "UPDATE privacy_request SET status = 'WAITING_FOR_INFO' WHERE id = ?", requestId);

    getAs("/api/privacy-requests/" + requestId, member.cookie())
        .andExpect(jsonPath("$.status").value("WAITING_FOR_INFO"))
        .andExpect(jsonPath("$.supplementRequest").value("가입 이메일과 휴대폰 뒤 네 자리를 알려 주세요"));

    jdbcTemplate.update("UPDATE privacy_request SET status = 'REVIEWING' WHERE id = ?", requestId);
    getAs("/api/privacy-requests/" + requestId, member.cookie())
        .andExpect(jsonPath("$.supplementRequest").doesNotExist());
  }

  @Test
  void 다른_사용자의_요청과_없는_요청은_404이고_숫자가_아닌_ID는_400이다() throws Exception {
    Member member = newMember("a@example.com");
    Member other = newMember("b@example.com");
    long requestId = create(other, "ACCESS").get("requestId").longValue();

    assertError(
        getAs("/api/privacy-requests/" + requestId, member.cookie()), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        getAs("/api/privacy-requests/99999", member.cookie()), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(getAs("/api/privacy-requests/abc", member.cookie()), ErrorCode.INVALID_REQUEST);
  }
}
