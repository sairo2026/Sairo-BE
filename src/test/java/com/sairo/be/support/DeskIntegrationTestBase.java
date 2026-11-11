package com.sairo.be.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.auth.entity.AppUser;
import com.sairo.be.domain.support.service.AttachmentScanService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

public abstract class DeskIntegrationTestBase extends ApproverIntegrationTestBase {

  @Autowired protected AttachmentScanService scanService;

  protected record Member(AppUser user, Cookie cookie) {

    public long id() {
      return user.getId();
    }
  }

  protected Member newMember(String email) {
    AppUser user = saveEmailUser(email, null);
    return new Member(user, kakaoSessionFor(user));
  }

  protected long count(String table) {
    Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    return total == null ? 0 : total;
  }

  protected long insertOperator(String name) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO operator_account (kakao_provider_key, name, status, mfa_enabled,"
                + " totp_secret_encrypted) VALUES (?, ?, 'ACTIVE', true, 'x') RETURNING id",
            Long.class,
            "kakao-" + name + "-" + System.nanoTime(),
            name);
    return id == null ? -1 : id;
  }

  protected ResultActions postJson(String url, String body, Cookie cookie) throws Exception {
    return mockMvc.perform(jsonPostAs(url, body, cookie));
  }

  protected ResultActions getAs(String url, Cookie cookie) throws Exception {
    return mockMvc.perform(authedGet(url, cookie));
  }

  protected long createInquiry(Cookie cookie, String category, String content) throws Exception {
    JsonNode created =
        AuthFlowSupport.json(
            postJson(
                    "/api/support-inquiries",
                    "{\"category\":\"" + category + "\",\"content\":\"" + content + "\"}",
                    cookie)
                .andExpect(status().isCreated())
                .andReturn());
    return created.get("inquiryId").longValue();
  }

  protected long insertMessage(
      long inquiryId,
      String authorType,
      Long userId,
      Long operatorId,
      String visibility,
      String content) {
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO support_inquiry_message (inquiry_id, author_type, author_user_id,"
                + " author_operator_id, visibility, content) VALUES (?, ?, ?, ?, ?, ?)"
                + " RETURNING id",
            Long.class,
            inquiryId,
            authorType,
            userId,
            operatorId,
            visibility,
            content);
    return id == null ? -1 : id;
  }

  protected long insertOperatorMessage(
      long inquiryId, long operatorId, String visibility, String content) {
    return insertMessage(inquiryId, "OPERATOR", null, operatorId, visibility, content);
  }

  protected long insertAttachment(
      long inquiryId,
      Long messageId,
      Long userId,
      Long operatorId,
      String scanStatus,
      String fileName,
      byte[] content) {
    String key = "support-inquiries/" + inquiryId + "/" + UUID.randomUUID();
    objectStorage.put(key, content, "application/pdf");
    Long id =
        jdbcTemplate.queryForObject(
            "INSERT INTO support_inquiry_attachment (inquiry_id, message_id, object_key,"
                + " file_name, media_type, size_bytes, uploaded_by_user_id,"
                + " uploaded_by_operator_id, scan_status) VALUES (?, ?, ?, ?, 'application/pdf',"
                + " ?, ?, ?, ?) RETURNING id",
            Long.class,
            inquiryId,
            messageId,
            key,
            fileName,
            (long) content.length,
            userId,
            operatorId,
            scanStatus);
    return id == null ? -1 : id;
  }

  protected void setInquiryState(long inquiryId, String status, Long assignedOperatorId) {
    jdbcTemplate.update(
        "UPDATE support_inquiry SET status = ?, assigned_operator_id = ?,"
            + " closed_at = CASE WHEN ? = 'CLOSED' THEN now() ELSE NULL END WHERE id = ?",
        status,
        assignedOperatorId,
        status,
        inquiryId);
  }

  protected String inquiryStatus(long inquiryId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM support_inquiry WHERE id = ?", String.class, inquiryId);
  }

  protected String scanStatusOf(long attachmentId) {
    return jdbcTemplate.queryForObject(
        "SELECT scan_status FROM support_inquiry_attachment WHERE id = ?",
        String.class,
        attachmentId);
  }

  protected ResultActions upload(
      Cookie cookie,
      long inquiryId,
      String fileName,
      String contentType,
      byte[] content,
      Long messageId)
      throws Exception {
    MockMultipartHttpServletRequestBuilder builder =
        multipart("/api/support-inquiries/" + inquiryId + "/attachments")
            .file(new MockMultipartFile("file", fileName, contentType, content));
    if (messageId != null) {
      builder.param("messageId", String.valueOf(messageId));
    }
    if (cookie != null) {
      builder.cookie(cookie);
    }
    return mockMvc.perform(
        builder
            .cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
            .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE));
  }

  protected ResultActions uploadPdf(Cookie cookie, long inquiryId, String fileName)
      throws Exception {
    return upload(cookie, inquiryId, fileName, "application/pdf", pdf("본문"), null);
  }

  protected static byte[] pdf(String text) {
    return ("%PDF-1.4\n" + text).getBytes(StandardCharsets.UTF_8);
  }

  protected static byte[] png() {
    return new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
  }

  protected static byte[] jpeg() {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F'};
  }
}
