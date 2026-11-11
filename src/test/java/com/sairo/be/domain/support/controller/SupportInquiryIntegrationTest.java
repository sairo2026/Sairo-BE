package com.sairo.be.domain.support.controller;

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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class SupportInquiryIntegrationTest extends DeskIntegrationTestBase {

  @Test
  void 로그인한_사용자가_문의를_접수하면_OPEN과_첫_답변_목표시각을_돌려준다() throws Exception {
    Member member = newMember("a@example.com");

    postJson(
            "/api/support-inquiries",
            "{\"category\":\"BILLING\",\"content\":\"환불이 늦어집니다\",\"requestScope\":\"3월 결제분\"}",
            member.cookie())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.inquiryId").isNumber())
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.responseDueAt").exists())
        .andExpect(jsonPath("$.officeId").doesNotExist())
        .andExpect(jsonPath("$.version").value(1));

    assertThat(count("support_inquiry")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT requester_user_id || ':' || priority || ':' || request_scope"
                    + " FROM support_inquiry",
                String.class))
        .isEqualTo(member.id() + ":NORMAL:3월 결제분");
  }

  @Test
  void 사무소_소속_사용자의_문의는_세션의_사무소로_접수한다() throws Exception {
    var owner = newStaff(OfficeMembershipRole.OWNER);

    postJson(
            "/api/support-inquiries",
            "{\"category\":\"BUSINESS\",\"content\":\"업무 문의\"}",
            cookieOf(owner))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.officeId").value(owner.office().getId()));
  }

  @Test
  void 요청_본문의_officeId는_허용하지_않는다() throws Exception {
    Member member = newMember("a@example.com");

    assertFieldError(
        postJson(
            "/api/support-inquiries",
            "{\"category\":\"BUG\",\"content\":\"오류\",\"officeId\":7}",
            member.cookie()),
        "officeId",
        "NOT_ALLOWED");
  }

  @Test
  void 문의_유형이_없거나_허용_값이_아니면_400이다() throws Exception {
    Member member = newMember("a@example.com");

    assertFieldError(
        postJson("/api/support-inquiries", "{\"content\":\"본문\"}", member.cookie()),
        "category",
        "REQUIRED");
    assertFieldError(
        postJson(
            "/api/support-inquiries", "{\"category\":\"ETC\",\"content\":\"본문\"}", member.cookie()),
        "category",
        "NOT_ALLOWED");
  }

  @Test
  void 본문이_비었거나_너무_길거나_HTML이면_400이다() throws Exception {
    Member member = newMember("a@example.com");

    assertFieldError(
        postJson(
            "/api/support-inquiries", "{\"category\":\"BUG\",\"content\":\"  \"}", member.cookie()),
        "content",
        "REQUIRED");
    assertFieldError(
        postJson(
            "/api/support-inquiries",
            "{\"category\":\"BUG\",\"content\":\"" + "가".repeat(10_001) + "\"}",
            member.cookie()),
        "content",
        "TOO_LONG");
    assertFieldError(
        postJson(
            "/api/support-inquiries",
            "{\"category\":\"BUG\",\"content\":\"<script>x</script>\"}",
            member.cookie()),
        "content",
        "HTML_NOT_ALLOWED");
    assertThat(count("support_inquiry")).isZero();
  }

  @Test
  void 세션이_없으면_모든_본인_문의_엔드포인트가_401이다() throws Exception {
    assertError(
        postJson("/api/support-inquiries", "{\"category\":\"BUG\",\"content\":\"a\"}", null),
        ErrorCode.UNAUTHENTICATED);
    assertError(getAs("/api/support-inquiries/me", null), ErrorCode.UNAUTHENTICATED);
    assertError(getAs("/api/support-inquiries/1", null), ErrorCode.UNAUTHENTICATED);
    assertError(
        postJson("/api/support-inquiries/1/replies", "{\"content\":\"a\"}", null),
        ErrorCode.UNAUTHENTICATED);
    assertError(uploadPdf(null, 1, "a.pdf"), ErrorCode.UNAUTHENTICATED);
    assertError(getAs("/api/support-inquiries/1/attachments/1", null), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 본인_문의_이력은_본인_것만_최신순으로_돌려준다() throws Exception {
    Member member = newMember("a@example.com");
    Member other = newMember("b@example.com");
    long first = createInquiry(member.cookie(), "AUTH", "첫 문의");
    long second = createInquiry(member.cookie(), "BILLING", "둘째 문의");
    createInquiry(other.cookie(), "BUG", "남의 문의");
    long operatorId = insertOperator("김지원");
    setInquiryState(second, "ASSIGNED", operatorId);

    getAs("/api/support-inquiries/me", member.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(25))
        .andExpect(jsonPath("$.items[0].inquiryId").value(second))
        .andExpect(jsonPath("$.items[0].category").value("BILLING"))
        .andExpect(jsonPath("$.items[0].status").value("ASSIGNED"))
        .andExpect(jsonPath("$.items[0].priority").value("NORMAL"))
        .andExpect(jsonPath("$.items[0].assignedOperatorName").value("김지원"))
        .andExpect(jsonPath("$.items[0].assignedOperatorId").doesNotExist())
        .andExpect(jsonPath("$.items[1].inquiryId").value(first))
        .andExpect(jsonPath("$.items[1].assignedOperatorName").doesNotExist())
        .andExpect(jsonPath("$.items[0].content").doesNotExist());
  }

  @Test
  void 목록은_페이지_크기를_25_50_100만_허용한다() throws Exception {
    Member member = newMember("a@example.com");

    assertFieldError(
        getAs("/api/support-inquiries/me?size=20", member.cookie()), "size", "NOT_ALLOWED");
    assertFieldError(
        getAs("/api/support-inquiries/me?page=-1", member.cookie()), "page", "OUT_OF_RANGE");
    getAs("/api/support-inquiries/me?size=50&page=1", member.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void 상세는_최초_본문과_요청_범위와_공개_메시지를_돌려준다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId =
        AuthFlowSupport.json(
                postJson(
                        "/api/support-inquiries",
                        "{\"category\":\"MIGRATION\",\"content\":\"이전 문의\",\"requestScope\":\"고객 시트\"}",
                        member.cookie())
                    .andReturn())
            .get("inquiryId")
            .longValue();
    long operatorId = insertOperator("김지원");
    insertOperatorMessage(inquiryId, operatorId, "PUBLIC", "확인 후 안내드립니다");

    getAs("/api/support-inquiries/" + inquiryId, member.cookie())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inquiryId").value(inquiryId))
        .andExpect(jsonPath("$.category").value("MIGRATION"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.content").value("이전 문의"))
        .andExpect(jsonPath("$.requestScope").value("고객 시트"))
        .andExpect(jsonPath("$.messages.length()").value(1))
        .andExpect(jsonPath("$.messages[0].visibility").value("PUBLIC"))
        .andExpect(jsonPath("$.messages[0].authorType").value("OPERATOR"))
        .andExpect(jsonPath("$.messages[0].authorDisplayName").value("김지원"))
        .andExpect(jsonPath("$.messages[0].authorOperatorId").doesNotExist())
        .andExpect(jsonPath("$.attachments").isEmpty())
        .andExpect(jsonPath("$.officeId").doesNotExist())
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void 다른_사용자의_문의와_없는_문의는_404이고_숫자가_아닌_ID는_400이다() throws Exception {
    Member member = newMember("a@example.com");
    Member other = newMember("b@example.com");
    long inquiryId = createInquiry(other.cookie(), "BUG", "남의 문의");

    assertError(
        getAs("/api/support-inquiries/" + inquiryId, member.cookie()),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        getAs("/api/support-inquiries/99999", member.cookie()), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(getAs("/api/support-inquiries/abc", member.cookie()), ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 답글은_공개_메시지로_추가하고_OPEN_티켓의_상태는_유지한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "AUTH", "로그인이 안 됩니다");

    postJson(
            "/api/support-inquiries/" + inquiryId + "/replies",
            "{\"content\":\"추가로 알립니다\"}",
            member.cookie())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.messageId").isNumber())
        .andExpect(jsonPath("$.inquiryId").value(inquiryId))
        .andExpect(jsonPath("$.visibility").value("PUBLIC"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.content").value("추가로 알립니다"))
        .andExpect(jsonPath("$.version").value(2));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT author_type || ':' || visibility || ':' || author_user_id"
                    + " FROM support_inquiry_message",
                String.class))
        .isEqualTo("REQUESTER:PUBLIC:" + member.id());
  }

  @Test
  void 답글은_완료_티켓을_접수로_재개한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "AUTH", "문의");
    long operatorId = insertOperator("김지원");
    setInquiryState(inquiryId, "CLOSED", operatorId);

    postJson(
            "/api/support-inquiries/" + inquiryId + "/replies",
            "{\"content\":\"다시 문의드립니다\"}",
            member.cookie())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("OPEN"));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT closed_at IS NULL FROM support_inquiry WHERE id = ?",
                Boolean.class,
                inquiryId))
        .isTrue();
  }

  @Test
  void 답글은_보완_대기와_답변됨_티켓을_담당자_유무에_따라_처리중_또는_접수로_되돌린다() throws Exception {
    Member member = newMember("a@example.com");
    long operatorId = insertOperator("김지원");
    long waiting = createInquiry(member.cookie(), "AUTH", "문의 1");
    setInquiryState(waiting, "WAITING_FOR_CUSTOMER", operatorId);
    long answeredUnassigned = createInquiry(member.cookie(), "AUTH", "문의 2");
    setInquiryState(answeredUnassigned, "ANSWERED", null);
    long assigned = createInquiry(member.cookie(), "AUTH", "문의 3");
    setInquiryState(assigned, "ASSIGNED", operatorId);

    postJson(
            "/api/support-inquiries/" + waiting + "/replies",
            "{\"content\":\"a\"}",
            member.cookie())
        .andExpect(jsonPath("$.status").value("ASSIGNED"));
    postJson(
            "/api/support-inquiries/" + answeredUnassigned + "/replies",
            "{\"content\":\"a\"}",
            member.cookie())
        .andExpect(jsonPath("$.status").value("OPEN"));
    postJson(
            "/api/support-inquiries/" + assigned + "/replies",
            "{\"content\":\"a\"}",
            member.cookie())
        .andExpect(jsonPath("$.status").value("ASSIGNED"));
  }

  @Test
  void 답글은_기존_문의_원문과_운영자_답변을_덮어쓰지_않는다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "AUTH", "원문");
    long operatorId = insertOperator("김지원");
    long operatorMessage = insertOperatorMessage(inquiryId, operatorId, "PUBLIC", "운영자 답변");
    setInquiryState(inquiryId, "ANSWERED", operatorId);
    postJson(
            "/api/support-inquiries/" + inquiryId + "/replies",
            "{\"content\":\"질문\"}",
            member.cookie())
        .andExpect(status().isCreated());

    JsonNode detail =
        AuthFlowSupport.json(
            getAs("/api/support-inquiries/" + inquiryId, member.cookie()).andReturn());
    assertThat(detail.get("content").asString()).isEqualTo("원문");
    assertThat(detail.get("messages")).hasSize(2);
    assertThat(detail.get("messages").get(0).get("messageId").longValue())
        .isEqualTo(operatorMessage);
    assertThat(detail.get("messages").get(0).get("content").asString()).isEqualTo("운영자 답변");
    assertThat(detail.get("messages").get(1).get("authorType").asString()).isEqualTo("REQUESTER");
    assertThat(detail.get("messages").get(1).get("authorDisplayName").isNull()).isTrue();
  }

  @Test
  void 답글_본문이_없거나_허용되지_않는_필드가_있으면_400이다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "AUTH", "문의");

    assertFieldError(
        postJson("/api/support-inquiries/" + inquiryId + "/replies", "{}", member.cookie()),
        "content",
        "REQUIRED");
    assertFieldError(
        postJson(
            "/api/support-inquiries/" + inquiryId + "/replies",
            "{\"content\":\"a\",\"visibility\":\"INTERNAL\"}",
            member.cookie()),
        "visibility",
        "NOT_ALLOWED");
    assertThat(count("support_inquiry_message")).isZero();
  }

  @Test
  void 남의_문의에는_답글을_쓸_수_없다() throws Exception {
    Member member = newMember("a@example.com");
    Member other = newMember("b@example.com");
    long inquiryId = createInquiry(other.cookie(), "AUTH", "남의 문의");

    assertError(
        postJson(
            "/api/support-inquiries/" + inquiryId + "/replies",
            "{\"content\":\"끼어들기\"}",
            member.cookie()),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(count("support_inquiry_message")).isZero();
  }
}
