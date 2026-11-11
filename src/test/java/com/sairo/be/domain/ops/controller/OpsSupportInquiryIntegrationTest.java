package com.sairo.be.domain.ops.controller;

import static com.sairo.be.support.AuthFlowSupport.jsonPatch;
import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.office.entity.OfficeMembershipRole;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.IntegrationTest;
import com.sairo.be.support.OpsConsoleTestBase;
import com.sairo.be.support.fixture.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OpsSupportInquiryIntegrationTest extends OpsConsoleTestBase {

  private static final String BASE = "/api/ops/support-inquiries";

  @Autowired private ObjectStorage objectStorage;

  private Operator support;
  private Cookie supportSession;
  private Operator other;
  private TestData.Staff owner;

  @BeforeEach
  void setUp() {
    support = operator("김지원", OperatorRole.SUPPORT);
    supportSession = sessionOf(support);
    other = operator("박상담", OperatorRole.SUPPORT);
    owner = staff(OfficeMembershipRole.OWNER);
    jdbcTemplate.update("UPDATE app_user SET name = '홍길동' WHERE id = ?", owner.user().getId());
  }

  private long inquiry() {
    return insertInquiry(owner.office().getId(), owner.user().getId());
  }

  private long version(long inquiryId) {
    return count("SELECT version FROM support_inquiry WHERE id = ?", inquiryId);
  }

  private ResultActions upload(
      long inquiryId, String name, String type, byte[] bytes, Long messageId) throws Exception {
    var builder =
        multipart(BASE + "/" + inquiryId + "/attachments")
            .file(new MockMultipartFile("file", name, type, bytes));
    if (messageId != null) {
      builder.param("messageId", String.valueOf(messageId));
    }
    return mockMvc.perform(
        builder
            .cookie(supportSession, new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
            .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE));
  }

  private static byte[] pdf() {
    return "%PDF-1.4\n본문".getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void 새_엔드포인트는_세션이_없으면_401이고_고객지원_역할이_없으면_403이다() throws Exception {
    Cookie privacy = sessionOf(operator("최개인", OperatorRole.PRIVACY));
    String[] gets = {BASE, BASE + "/1", BASE + "/1/attachments/1"};
    String[] posts = {BASE + "/1/assign", BASE + "/1/answer", BASE + "/1/notes"};
    for (String path : gets) {
      assertError(mockMvc.perform(get(path)), ErrorCode.UNAUTHENTICATED);
      assertError(mockMvc.perform(get(path).cookie(privacy)), ErrorCode.FORBIDDEN);
    }
    for (String path : posts) {
      assertError(post(path, "{}", null), ErrorCode.UNAUTHENTICATED);
      assertError(post(path, "{}", privacy), ErrorCode.FORBIDDEN);
    }
    assertError(
        mockMvc.perform(jsonPatch(BASE + "/1/priority", "{\"priority\":\"HIGH\"}")),
        ErrorCode.UNAUTHENTICATED);
    assertError(
        mockMvc.perform(jsonPatch(BASE + "/1/priority", "{\"priority\":\"HIGH\"}", privacy)),
        ErrorCode.FORBIDDEN);
    assertError(mockMvc.perform(get(BASE).cookie(memberSession(owner))), ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void 목록은_필터를_적용하고_작성자_이름을_가린다() throws Exception {
    long first = inquiry();
    long second = inquiry();
    jdbcTemplate.update(
        "UPDATE support_inquiry SET response_due_at = now() - interval '1 hour', category ="
            + " 'BILLING', priority = 'HIGH' WHERE id = ?",
        second);
    jdbcTemplate.update(
        "UPDATE support_inquiry SET assigned_operator_id = ?, status = 'ASSIGNED' WHERE id = ?",
        support.id(),
        first);

    mockMvc
        .perform(get(BASE).cookie(supportSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.items[0].inquiryId").value(second))
        .andExpect(jsonPath("$.items[0].authorDisplayName").value("홍*동"))
        .andExpect(jsonPath("$.items[0].officeName").value(owner.office().getName()))
        .andExpect(jsonPath("$.items[1].assignedOperatorName").value("김지원"));
    mockMvc
        .perform(get(BASE).param("overdue", "true").cookie(supportSession))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].inquiryId").value(second));
    mockMvc
        .perform(get(BASE).param("unassigned", "true").cookie(supportSession))
        .andExpect(jsonPath("$.items[0].inquiryId").value(second));
    mockMvc
        .perform(
            get(BASE)
                .param("assignedOperatorId", String.valueOf(support.id()))
                .cookie(supportSession))
        .andExpect(jsonPath("$.items[0].inquiryId").value(first));
    mockMvc
        .perform(
            get(BASE)
                .param("category", "BILLING")
                .param("priority", "HIGH")
                .param("officeId", String.valueOf(owner.office().getId()))
                .cookie(supportSession))
        .andExpect(jsonPath("$.totalElements").value(1));
    assertFieldError(
        mockMvc.perform(get(BASE).param("category", "NOPE").cookie(supportSession)),
        "category",
        "NOT_ALLOWED");
    assertFieldError(
        mockMvc.perform(get(BASE).param("size", "7").cookie(supportSession)),
        "size",
        "NOT_ALLOWED");
  }

  @Test
  void 상세는_내부_메모와_첨부를_보이고_조회를_감사_로그에_남긴다() throws Exception {
    long id = inquiry();
    post(BASE + "/" + id + "/notes", "{\"content\":\"결제팀 확인 요청\"}", supportSession)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.visibility").value("INTERNAL"))
        .andExpect(jsonPath("$.inquiryId").value(id));

    mockMvc
        .perform(get(BASE + "/" + id).cookie(supportSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("문의 본문"))
        .andExpect(jsonPath("$.authorUserId").value(owner.user().getId()))
        .andExpect(jsonPath("$.messages[0].visibility").value("INTERNAL"))
        .andExpect(jsonPath("$.messages[0].authorType").value("OPERATOR"))
        .andExpect(jsonPath("$.messages[0].authorDisplayName").value("김지원"))
        .andExpect(jsonPath("$.attachments").isArray());
    assertThat(auditCount("SUPPORT_INQUIRY_VIEWED")).isEqualTo(1);
    assertThat(auditCount("SUPPORT_INQUIRY_NOTE_ADDED")).isEqualTo(1);
    assertError(
        mockMvc.perform(get(BASE + "/999999").cookie(supportSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        post(BASE + "/" + id + "/notes", "{\"content\":\" \"}", supportSession),
        ErrorCode.INVALID_REQUEST);
  }

  @Test
  void 배정은_활성_고객지원_운영자만_받고_종료한_문의는_409다() throws Exception {
    long id = inquiry();
    Operator billing = operator("이결제", OperatorRole.BILLING);
    assertError(
        post(
            BASE + "/" + id + "/assign",
            "{\"assigneeOperatorId\":" + billing.id() + "}",
            supportSession),
        ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    assertError(
        post(BASE + "/" + id + "/assign", "{\"assigneeOperatorId\":999999}", supportSession),
        ErrorCode.ASSIGNEE_NOT_ELIGIBLE);
    assertError(post(BASE + "/" + id + "/assign", "{}", supportSession), ErrorCode.INVALID_REQUEST);
    assertError(
        post(
            BASE + "/999999/assign", "{\"assigneeOperatorId\":" + other.id() + "}", supportSession),
        ErrorCode.RESOURCE_NOT_FOUND);

    post(BASE + "/" + id + "/assign", "{\"assigneeOperatorId\":" + other.id() + "}", supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.assigneeOperatorId").value(other.id()));
    assertThat(auditCount("SUPPORT_INQUIRY_ASSIGNED")).isEqualTo(1);

    jdbcTemplate.update(
        "UPDATE support_inquiry SET status = 'CLOSED', closed_at = now() WHERE id = ?", id);
    assertError(
        post(
            BASE + "/" + id + "/assign",
            "{\"assigneeOperatorId\":" + support.id() + "}",
            supportSession),
        ErrorCode.INVALID_TRANSITION);
    assertError(
        post(BASE + "/" + id + "/notes", "{\"content\":\"메모\"}", supportSession),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 답변은_새_공개_메시지를_남기고_상태를_바꾸며_고객에게_알린다() throws Exception {
    long id = inquiry();
    assertError(
        post(
            BASE + "/" + id + "/answer",
            "{\"content\":\"확인\",\"needsCustomerReply\":true,\"closeAfterReply\":true}",
            supportSession),
        ErrorCode.INVALID_REQUEST);
    post(
            BASE + "/" + id + "/answer",
            "{\"content\":\"확인 후 안내드립니다\",\"needsCustomerReply\":true,\"closeAfterReply\":false}",
            supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITING_FOR_CUSTOMER"))
        .andExpect(jsonPath("$.messages[0].visibility").value("PUBLIC"))
        .andExpect(jsonPath("$.answeredAt").exists());
    post(
            BASE + "/" + id + "/answer",
            "{\"content\":\"처리했습니다\",\"needsCustomerReply\":false,\"closeAfterReply\":true}",
            supportSession)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.messages.length()").value(2));
    assertThat(
            count(
                "SELECT count(*) FROM support_inquiry_message WHERE inquiry_id = ? AND visibility ="
                    + " 'PUBLIC' AND author_operator_id = ?",
                id,
                support.id()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM notification WHERE recipient_user_id = ? AND"
                    + " notification_type = 'SUPPORT_REPLY' AND target_id = ?",
                owner.user().getId(),
                id))
        .isEqualTo(2);
    assertError(
        post(
            BASE + "/" + id + "/answer",
            "{\"content\":\"추가\",\"needsCustomerReply\":false,\"closeAfterReply\":false}",
            supportSession),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 우선순위는_If_Match가_필요하고_답변_전이면_목표_시각을_다시_계산한다() throws Exception {
    long id = inquiry();
    String path = BASE + "/" + id + "/priority";
    String body = "{\"priority\":\"HIGH\",\"reason\":\"결제 오류\"}";
    assertFieldError(
        mockMvc.perform(jsonPatch(path, body, supportSession)), "If-Match", "REQUIRED");
    assertError(
        mockMvc.perform(jsonPatch(path, body, supportSession).header("If-Match", "99")),
        ErrorCode.VERSION_CONFLICT);
    assertError(
        mockMvc.perform(
            jsonPatch(path, "{\"priority\":\"URGENT\"}", supportSession)
                .header("If-Match", String.valueOf(version(id)))),
        ErrorCode.INVALID_REQUEST);

    long before = version(id);
    mockMvc
        .perform(jsonPatch(path, body, supportSession).header("If-Match", String.valueOf(before)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value("HIGH"))
        .andExpect(jsonPath("$.version").value(before + 1));
    assertThat(
            count(
                "SELECT count(*) FROM support_inquiry WHERE id = ? AND response_due_at ="
                    + " created_at + interval '4 hours'",
                id))
        .isEqualTo(1);
    assertThat(auditCount("SUPPORT_INQUIRY_PRIORITY_CHANGED")).isEqualTo(1);

    jdbcTemplate.update(
        "UPDATE support_inquiry SET status = 'CLOSED', closed_at = now() WHERE id = ?", id);
    assertError(
        mockMvc.perform(
            jsonPatch(path, "{\"priority\":\"LOW\"}", supportSession)
                .header("If-Match", String.valueOf(version(id)))),
        ErrorCode.INVALID_TRANSITION);
  }

  @Test
  void 운영자_첨부는_5개까지_받고_위반은_file_항목_400이다() throws Exception {
    long id = inquiry();
    String created =
        upload(id, "안내.pdf", "application/pdf", pdf(), null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.scanStatus").value("PENDING"))
            .andExpect(jsonPath("$.messageId").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long attachmentId = Long.parseLong(created.replaceAll("(?s).*\"attachmentId\":(\\d+).*", "$1"));
    assertThat(
            count(
                "SELECT count(*) FROM support_inquiry_attachment WHERE id = ? AND"
                    + " uploaded_by_operator_id = ?",
                attachmentId,
                support.id()))
        .isEqualTo(1);

    assertFieldError(
        upload(id, "a.exe", "application/octet-stream", pdf(), null), "file", "NOT_ALLOWED");
    assertFieldError(
        upload(id, "a.pdf", "application/pdf", "not pdf".getBytes(StandardCharsets.UTF_8), null),
        "file",
        "NOT_ALLOWED");
    assertError(
        upload(id, "a.pdf", "application/pdf", pdf(), 999999L), ErrorCode.RESOURCE_NOT_FOUND);
    for (int i = 0; i < 4; i++) {
      upload(id, "추가" + i + ".pdf", "application/pdf", pdf(), null).andExpect(status().isCreated());
    }
    assertFieldError(upload(id, "초과.pdf", "application/pdf", pdf(), null), "file", "OUT_OF_RANGE");

    long other = inquiry();
    String note =
        post(BASE + "/" + other + "/notes", "{\"content\":\"내부 메모\"}", supportSession)
            .andReturn()
            .getResponse()
            .getContentAsString();
    long messageId = Long.parseLong(note.replaceAll("(?s).*\"messageId\":(\\d+).*", "$1"));
    upload(other, "메모첨부.pdf", "application/pdf", pdf(), messageId)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.messageId").value(messageId));
    mockMvc
        .perform(get(BASE + "/" + other).cookie(supportSession))
        .andExpect(jsonPath("$.attachments[0].visibility").value("INTERNAL"));
    assertError(
        upload(999999L, "a.pdf", "application/pdf", pdf(), null), ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 다운로드는_검사_완료_첨부만_전달하고_감사_로그에_남긴다() throws Exception {
    long id = inquiry();
    objectStorage.put("support-inquiries/" + id + "/file.pdf", pdf(), "application/pdf");
    long attachmentId =
        jdbcTemplate.queryForObject(
            "INSERT INTO support_inquiry_attachment (inquiry_id, object_key, file_name, media_type,"
                + " size_bytes, uploaded_by_user_id, scan_status) VALUES (?, ?, '증빙.pdf',"
                + " 'application/pdf', ?, ?, 'PENDING') RETURNING id",
            Long.class,
            id,
            "support-inquiries/" + id + "/file.pdf",
            pdf().length,
            owner.user().getId());
    String path = BASE + "/" + id + "/attachments/" + attachmentId;
    assertError(mockMvc.perform(get(path).cookie(supportSession)), ErrorCode.ATTACHMENT_NOT_READY);
    jdbcTemplate.update(
        "UPDATE support_inquiry_attachment SET scan_status = 'CLEAN' WHERE id = ?", attachmentId);
    mockMvc
        .perform(get(path).cookie(supportSession))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/pdf"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(content().bytes(pdf()));
    assertThat(auditCount("SUPPORT_ATTACHMENT_DOWNLOADED")).isEqualTo(1);
    long otherInquiry = inquiry();
    assertError(
        mockMvc.perform(
            get(BASE + "/" + otherInquiry + "/attachments/" + attachmentId).cookie(supportSession)),
        ErrorCode.RESOURCE_NOT_FOUND);
  }
}
