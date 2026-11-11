package com.sairo.be.domain.support.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.support.DeskIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class SupportInternalNoteIsolationTest extends DeskIntegrationTestBase {

  private static final String INTERNAL_NOTE = "내부메모-고객에게-보이면-안-됨";
  private static final String INTERNAL_FILE = "내부증빙-비공개.pdf";
  private static final String UNLINKED_OPERATOR_FILE = "운영자-메시지연결없음.pdf";
  private static final String PENDING_OPERATOR_FILE = "운영자-검사중.pdf";
  private static final List<String> FORBIDDEN =
      List.of(
          INTERNAL_NOTE,
          INTERNAL_FILE,
          UNLINKED_OPERATOR_FILE,
          PENDING_OPERATOR_FILE,
          "INTERNAL",
          "objectKey",
          "support-inquiries/");

  private Member member;
  private long inquiryId;
  private long operatorId;
  private long internalMessageId;
  private long publicMessageId;
  private long internalAttachmentId;
  private long unlinkedOperatorAttachmentId;
  private long pendingOperatorAttachmentId;
  private long publicOperatorAttachmentId;
  private long ownPendingAttachmentId;
  private long ownRejectedAttachmentId;

  @BeforeEach
  void prepareTicketWithInternalRecords() throws Exception {
    member = newMember("owner@example.com");
    inquiryId = createInquiry(member.cookie(), "BILLING", "결제가 두 번 됐습니다");
    operatorId = insertOperator("김지원");
    setInquiryState(inquiryId, "ASSIGNED", operatorId);
    internalMessageId = insertOperatorMessage(inquiryId, operatorId, "INTERNAL", INTERNAL_NOTE);
    publicMessageId = insertOperatorMessage(inquiryId, operatorId, "PUBLIC", "확인 중입니다");
    internalAttachmentId =
        insertAttachment(
            inquiryId, internalMessageId, null, operatorId, "CLEAN", INTERNAL_FILE, pdf("a"));
    unlinkedOperatorAttachmentId =
        insertAttachment(
            inquiryId, null, null, operatorId, "CLEAN", UNLINKED_OPERATOR_FILE, pdf("b"));
    pendingOperatorAttachmentId =
        insertAttachment(
            inquiryId,
            publicMessageId,
            null,
            operatorId,
            "PENDING",
            PENDING_OPERATOR_FILE,
            pdf("c"));
    publicOperatorAttachmentId =
        insertAttachment(
            inquiryId, publicMessageId, null, operatorId, "CLEAN", "공개-안내.pdf", pdf("d"));
    ownPendingAttachmentId =
        insertAttachment(inquiryId, null, member.id(), null, "PENDING", "내-검사중.pdf", pdf("e"));
    ownRejectedAttachmentId =
        insertAttachment(inquiryId, null, member.id(), null, "REJECTED", "내-거부됨.pdf", pdf("f"));
  }

  private static void assertNoInternalExposure(String responseBody) {
    for (String forbidden : FORBIDDEN) {
      assertThat(responseBody).as("응답에 노출되면 안 되는 값: " + forbidden).doesNotContain(forbidden);
    }
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  @Test
  void 고객_상세는_공개_메시지만_돌려주고_내부_메모_문구는_어디에도_없다() throws Exception {
    String responseBody =
        body(
            getAs("/api/support-inquiries/" + inquiryId, member.cookie())
                .andExpect(status().isOk()));

    assertNoInternalExposure(responseBody);
    JsonNode detail = new tools.jackson.databind.ObjectMapper().readTree(responseBody);
    assertThat(detail.get("messages")).hasSize(1);
    assertThat(detail.get("messages").get(0).get("visibility").asString()).isEqualTo("PUBLIC");
    assertThat(detail.get("messages").get(0).get("content").asString()).isEqualTo("확인 중입니다");
  }

  @Test
  void 고객_상세의_첨부는_공개_첨부와_본인_업로드분만이고_내부_첨부는_없다() throws Exception {
    JsonNode detail =
        new tools.jackson.databind.ObjectMapper()
            .readTree(
                body(
                    getAs("/api/support-inquiries/" + inquiryId, member.cookie())
                        .andExpect(status().isOk())));

    List<String> names = new ArrayList<>();
    detail.get("attachments").forEach(item -> names.add(item.get("fileName").asString()));
    assertThat(names).containsExactlyInAnyOrder("공개-안내.pdf", "내-검사중.pdf", "내-거부됨.pdf");
    detail
        .get("attachments")
        .forEach(
            item -> {
              if (item.get("fileName").asString().equals("내-검사중.pdf")) {
                assertThat(item.get("scanStatus").asString()).isEqualTo("PENDING");
              }
              if (item.get("fileName").asString().equals("내-거부됨.pdf")) {
                assertThat(item.get("scanStatus").asString()).isEqualTo("REJECTED");
              }
            });
  }

  @Test
  void 내부_메모_첨부와_메시지_연결이_없는_운영자_첨부와_검사_중인_운영자_첨부는_다운로드할_수_없다() throws Exception {
    for (long hidden :
        List.of(internalAttachmentId, unlinkedOperatorAttachmentId, pendingOperatorAttachmentId)) {
      assertError(
          getAs("/api/support-inquiries/" + inquiryId + "/attachments/" + hidden, member.cookie()),
          ErrorCode.RESOURCE_NOT_FOUND);
    }
    getAs(
            "/api/support-inquiries/" + inquiryId + "/attachments/" + publicOperatorAttachmentId,
            member.cookie())
        .andExpect(status().isOk());
  }

  @Test
  void 고객_목록_응답에는_내부_메모_정보가_없다() throws Exception {
    assertNoInternalExposure(
        body(getAs("/api/support-inquiries/me", member.cookie()).andExpect(status().isOk())));
  }

  @Test
  void 고객이_답글을_써도_응답과_이후_상세에_내부_기록이_섞이지_않는다() throws Exception {
    String replyBody =
        body(
            postJson(
                    "/api/support-inquiries/" + inquiryId + "/replies",
                    "{\"content\":\"추가 내용입니다\"}",
                    member.cookie())
                .andExpect(status().isCreated()));
    String detailBody =
        body(
            getAs("/api/support-inquiries/" + inquiryId, member.cookie())
                .andExpect(status().isOk()));

    assertNoInternalExposure(replyBody);
    assertNoInternalExposure(detailBody);
  }

  @Test
  void 고객이_첨부를_올려도_응답과_이후_상세에_내부_기록이_섞이지_않는다() throws Exception {
    String uploadBody =
        body(uploadPdf(member.cookie(), inquiryId, "추가-자료.pdf").andExpect(status().isCreated()));
    String detailBody =
        body(
            getAs("/api/support-inquiries/" + inquiryId, member.cookie())
                .andExpect(status().isOk()));

    assertNoInternalExposure(uploadBody);
    assertNoInternalExposure(detailBody);
  }

  @Test
  void 고객은_내부_메모_메시지에_첨부를_연결할_수_없고_메시지_존재도_알_수_없다() throws Exception {
    long before = count("support_inquiry_attachment");

    assertError(
        upload(member.cookie(), inquiryId, "a.pdf", "application/pdf", pdf("x"), internalMessageId),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        upload(member.cookie(), inquiryId, "a.pdf", "application/pdf", pdf("x"), publicMessageId),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertThat(count("support_inquiry_attachment")).isEqualTo(before);
  }

  @Test
  void 다른_사용자는_공개_첨부와_내부_첨부_모두_접근할_수_없다() throws Exception {
    Member stranger = newMember("stranger@example.com");

    assertError(
        getAs("/api/support-inquiries/" + inquiryId, stranger.cookie()),
        ErrorCode.RESOURCE_NOT_FOUND);
    for (long attachmentId : List.of(internalAttachmentId, publicOperatorAttachmentId)) {
      assertError(
          getAs(
              "/api/support-inquiries/" + inquiryId + "/attachments/" + attachmentId,
              stranger.cookie()),
          ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  @Test
  void 개인정보_권리_요청으로_만든_티켓도_같은_규칙으로_내부_기록을_숨긴다() throws Exception {
    long privacyInquiry =
        AuthFlowSupportJson.inquiryIdOf(
            postJson(
                    "/api/privacy-requests",
                    "{\"requestType\":\"ACCESS\",\"content\":\"열람 요청\"}",
                    member.cookie())
                .andExpect(status().isCreated()));
    insertOperatorMessage(privacyInquiry, operatorId, "INTERNAL", INTERNAL_NOTE);

    assertNoInternalExposure(
        body(
            getAs("/api/support-inquiries/" + privacyInquiry, member.cookie())
                .andExpect(status().isOk())));
    assertNoInternalExposure(
        body(getAs("/api/support-inquiries/me", member.cookie()).andExpect(status().isOk())));
  }

  private static final class AuthFlowSupportJson {

    static long inquiryIdOf(ResultActions result) throws Exception {
      JsonNode created =
          new tools.jackson.databind.ObjectMapper()
              .readTree(result.andReturn().getResponse().getContentAsString());
      return created.get("supportInquiryId").longValue();
    }
  }
}
