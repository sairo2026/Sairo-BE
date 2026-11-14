package com.sairo.be.domain.support.controller;

import static com.sairo.be.support.ErrorResponseAssertions.assertError;
import static com.sairo.be.support.ErrorResponseAssertions.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sairo.be.domain.support.entity.ScanStatus;
import com.sairo.be.domain.support.repository.SupportInquiryAttachmentRepository;
import com.sairo.be.domain.support.service.AttachmentScanService;
import com.sairo.be.global.error.ErrorCode;
import com.sairo.be.global.port.AttachmentScanner;
import com.sairo.be.global.port.ObjectStorage;
import com.sairo.be.global.port.stub.StubAttachmentScanner;
import com.sairo.be.support.AuthFlowSupport;
import com.sairo.be.support.DeskIntegrationTestBase;
import com.sairo.be.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class SupportAttachmentIntegrationTest extends DeskIntegrationTestBase {

  @Autowired private SupportInquiryAttachmentRepository attachmentRepository;
  @Autowired private AttachmentScanner scanner;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private Clock clock;

  private static final class DeleteFailingStorage implements ObjectStorage {
    private final ObjectStorage delegate;

    DeleteFailingStorage(ObjectStorage delegate) {
      this.delegate = delegate;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
      delegate.put(key, content, contentType);
    }

    @Override
    public Optional<byte[]> find(String key) {
      return delegate.find(key);
    }

    @Override
    public URI issueDownloadUrl(String key, Duration timeToLive) {
      return delegate.issueDownloadUrl(key, timeToLive);
    }

    @Override
    public void delete(String key) {
      throw new IllegalStateException("저장소 장애");
    }
  }

  private String objectKeyOf(long attachmentId) {
    return jdbcTemplate.queryForObject(
        "SELECT object_key FROM support_inquiry_attachment WHERE id = ?",
        String.class,
        attachmentId);
  }

  private boolean objectDeleted(long attachmentId) {
    return jdbcTemplate.queryForObject(
        "SELECT object_deleted_at IS NOT NULL FROM support_inquiry_attachment WHERE id = ?",
        Boolean.class,
        attachmentId);
  }

  private long uploadAndGetId(Cookie cookie, long inquiryId, String name, byte[] content)
      throws Exception {
    JsonNode created =
        AuthFlowSupport.json(
            upload(cookie, inquiryId, name, "application/pdf", content, null)
                .andExpect(status().isCreated())
                .andReturn());
    return created.get("attachmentId").longValue();
  }

  @Test
  void PDF를_올리면_검사_중_상태로_등록하고_작성자를_기록한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BILLING", "증빙 첨부");

    upload(member.cookie(), inquiryId, "환불요청서.pdf", "application/pdf", pdf("본문"), null)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.attachmentId").isNumber())
        .andExpect(jsonPath("$.inquiryId").value(inquiryId))
        .andExpect(jsonPath("$.messageId").doesNotExist())
        .andExpect(jsonPath("$.scanStatus").value("PENDING"))
        .andExpect(jsonPath("$.fileName").value("환불요청서.pdf"))
        .andExpect(jsonPath("$.mediaType").value("application/pdf"))
        .andExpect(jsonPath("$.sizeBytes").value(pdf("본문").length))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.objectKey").doesNotExist());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT uploaded_by_user_id || ':' || coalesce(uploaded_by_operator_id::text, '-')"
                    + " || ':' || scan_status FROM support_inquiry_attachment",
                String.class))
        .isEqualTo(member.id() + ":-:PENDING");
    String key =
        jdbcTemplate.queryForObject(
            "SELECT object_key FROM support_inquiry_attachment", String.class);
    assertThat(objectStorage.find(key)).isPresent();
  }

  @Test
  void JPG와_PNG도_받는다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "화면 캡처");

    upload(member.cookie(), inquiryId, "화면.JPG", "image/jpeg", jpeg(), null)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.mediaType").value("image/jpeg"));
    upload(member.cookie(), inquiryId, "화면.png", "image/png", png(), null)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.mediaType").value("image/png"));
    upload(member.cookie(), inquiryId, "화면.jpeg", "image/jpg", jpeg(), null)
        .andExpect(status().isCreated());
  }

  @Test
  void 허용하지_않는_형식은_422_ATTACHMENT_TYPE_NOT_ALLOWED다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");

    assertError(
        upload(member.cookie(), inquiryId, "a.txt", "text/plain", pdf("x"), null),
        ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    assertError(
        upload(member.cookie(), inquiryId, "a.exe", "application/octet-stream", pdf("x"), null),
        ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    assertError(
        upload(member.cookie(), inquiryId, "noext", "application/pdf", pdf("x"), null),
        ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    assertError(
        upload(member.cookie(), inquiryId, "a.pdf", "image/png", pdf("x"), null),
        ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    assertError(
        upload(
            member.cookie(),
            inquiryId,
            "a.pdf",
            "application/pdf",
            "MZ-not-a-pdf".getBytes(StandardCharsets.UTF_8),
            null),
        ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    assertError(
        upload(member.cookie(), inquiryId, "a.png", "image/png", jpeg(), null),
        ErrorCode.ATTACHMENT_TYPE_NOT_ALLOWED);
    assertThat(count("support_inquiry_attachment")).isZero();
  }

  @Test
  void 파일당_20MB를_넘으면_422_ATTACHMENT_TOO_LARGE이고_20MB는_받는다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    int limit = 20 * 1024 * 1024;
    byte[] exact = new byte[limit];
    byte[] header = pdf("");
    System.arraycopy(header, 0, exact, 0, header.length);
    byte[] tooLarge = new byte[limit + 1];
    System.arraycopy(header, 0, tooLarge, 0, header.length);

    assertError(
        upload(member.cookie(), inquiryId, "big.pdf", "application/pdf", tooLarge, null),
        ErrorCode.ATTACHMENT_TOO_LARGE);
    assertThat(count("support_inquiry_attachment")).isZero();
    upload(member.cookie(), inquiryId, "exact.pdf", "application/pdf", exact, null)
        .andExpect(status().isCreated());
  }

  @Test
  void 티켓당_10개를_넘으면_422_ATTACHMENT_LIMIT_EXCEEDED이고_운영자_첨부는_세지_않는다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long operatorId = insertOperator("김지원");
    for (int i = 0; i < 10; i++) {
      insertAttachment(inquiryId, null, null, operatorId, "CLEAN", "ops" + i + ".pdf", pdf("o"));
    }
    for (int i = 0; i < 9; i++) {
      uploadAndGetId(member.cookie(), inquiryId, "mine" + i + ".pdf", pdf("m" + i));
    }

    uploadPdf(member.cookie(), inquiryId, "tenth.pdf").andExpect(status().isCreated());
    assertError(
        uploadPdf(member.cookie(), inquiryId, "eleventh.pdf"), ErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
    assertThat(count("support_inquiry_attachment")).isEqualTo(20);
  }

  @Test
  void 남의_문의와_없는_문의는_404이고_파일이_없으면_400이다() throws Exception {
    Member member = newMember("a@example.com");
    Member other = newMember("b@example.com");
    long othersInquiry = createInquiry(other.cookie(), "BUG", "남의 문의");
    long mine = createInquiry(member.cookie(), "BUG", "내 문의");

    assertError(uploadPdf(member.cookie(), othersInquiry, "a.pdf"), ErrorCode.RESOURCE_NOT_FOUND);
    assertError(uploadPdf(member.cookie(), 99999, "a.pdf"), ErrorCode.RESOURCE_NOT_FOUND);
    assertFieldError(
        mockMvc.perform(
            multipart("/api/support-inquiries/" + mine + "/attachments")
                .param("messageId", "")
                .cookie(member.cookie())
                .cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
                .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE)),
        "file",
        "REQUIRED");
    assertFieldError(
        upload(member.cookie(), mine, "a.pdf", "application/pdf", new byte[0], null),
        "file",
        "REQUIRED");
  }

  @Test
  void 후속_답글의_메시지_ID를_지정하면_그_메시지에_연결한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long messageId =
        AuthFlowSupport.json(
                postJson(
                        "/api/support-inquiries/" + inquiryId + "/replies",
                        "{\"content\":\"추가\"}",
                        member.cookie())
                    .andReturn())
            .get("messageId")
            .longValue();

    upload(member.cookie(), inquiryId, "a.pdf", "application/pdf", pdf("x"), messageId)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.messageId").value(messageId));
    assertFieldError(
        mockMvc.perform(
            multipart("/api/support-inquiries/" + inquiryId + "/attachments")
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "file", "a.pdf", "application/pdf", pdf("x")))
                .param("messageId", "abc")
                .cookie(member.cookie())
                .cookie(new Cookie("XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE))
                .header("X-XSRF-TOKEN", AuthFlowSupport.CSRF_VALUE)),
        "messageId",
        "TYPE_MISMATCH");
  }

  @Test
  void 첨부를_올리면_완료_티켓을_접수로_재개한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    setInquiryState(inquiryId, "CLOSED", insertOperator("김지원"));

    uploadPdf(member.cookie(), inquiryId, "a.pdf").andExpect(status().isCreated());

    assertThat(inquiryStatus(inquiryId)).isEqualTo("OPEN");
  }

  @Test
  void 검사_중인_첨부는_내려받을_수_없고_통과하면_내려받는다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    byte[] original = pdf("환불 증빙");
    long attachmentId = uploadAndGetId(member.cookie(), inquiryId, "증빙 자료.pdf", original);
    String url = "/api/support-inquiries/" + inquiryId + "/attachments/" + attachmentId;

    assertError(getAs(url, member.cookie()), ErrorCode.ATTACHMENT_NOT_READY);

    assertThat(scanService.scan(attachmentId)).isEqualTo(ScanStatus.CLEAN);
    assertThat(scanStatusOf(attachmentId)).isEqualTo("CLEAN");
    getAs(url, member.cookie())
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/pdf"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("attachment"),
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''"))))
        .andExpect(content().bytes(original));
  }

  @Test
  void 악성으로_판정한_첨부는_REJECTED가_되고_내려받을_수_없다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long attachmentId =
        uploadAndGetId(
            member.cookie(), inquiryId, "의심.pdf", pdf(StubAttachmentScanner.REJECT_MARKER));

    assertThat(scanService.scan(attachmentId)).isEqualTo(ScanStatus.REJECTED);

    assertError(
        getAs(
            "/api/support-inquiries/" + inquiryId + "/attachments/" + attachmentId,
            member.cookie()),
        ErrorCode.ATTACHMENT_REJECTED);
    getAs("/api/support-inquiries/" + inquiryId, member.cookie())
        .andExpect(jsonPath("$.attachments[0].fileName").value("의심.pdf"))
        .andExpect(jsonPath("$.attachments[0].scanStatus").value("REJECTED"));
  }

  @Test
  void 거부로_판정하면_저장소_객체를_삭제하고_행과_판정은_남긴다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long attachmentId =
        uploadAndGetId(
            member.cookie(), inquiryId, "의심.pdf", pdf(StubAttachmentScanner.REJECT_MARKER));
    String key = objectKeyOf(attachmentId);
    assertThat(objectStorage.find(key)).isPresent();

    assertThat(scanService.scan(attachmentId)).isEqualTo(ScanStatus.REJECTED);

    assertThat(objectStorage.find(key)).isEmpty();
    assertThat(objectDeleted(attachmentId)).isTrue();
    assertThat(scanStatusOf(attachmentId)).isEqualTo("REJECTED");
    assertThat(scanService.purgeRejectedObjects()).isZero();
  }

  @Test
  void 객체_삭제가_실패해도_거부_판정은_유지하고_다음_주기에_다시_삭제한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long attachmentId =
        uploadAndGetId(
            member.cookie(), inquiryId, "의심.pdf", pdf(StubAttachmentScanner.REJECT_MARKER));
    String key = objectKeyOf(attachmentId);
    AttachmentScanService failing =
        new AttachmentScanService(
            attachmentRepository,
            new DeleteFailingStorage(objectStorage),
            scanner,
            transactionManager,
            clock);

    assertThat(failing.scan(attachmentId)).isEqualTo(ScanStatus.REJECTED);

    assertThat(scanStatusOf(attachmentId)).isEqualTo("REJECTED");
    assertThat(objectStorage.find(key)).isPresent();
    assertThat(objectDeleted(attachmentId)).isFalse();
    assertThat(failing.purgeRejectedObjects()).isEqualTo(1);
    assertThat(objectDeleted(attachmentId)).isFalse();

    assertThat(scanService.purgeRejectedObjects()).isEqualTo(1);

    assertThat(objectStorage.find(key)).isEmpty();
    assertThat(objectDeleted(attachmentId)).isTrue();
    assertThat(scanStatusOf(attachmentId)).isEqualTo("REJECTED");
    assertThat(scanService.purgeRejectedObjects()).isZero();
  }

  @Test
  void 검사가_끝난_첨부는_다시_검사하지_않는다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long attachmentId = uploadAndGetId(member.cookie(), inquiryId, "a.pdf", pdf("정상"));
    scanService.scan(attachmentId);
    jdbcTemplate.update(
        "UPDATE support_inquiry_attachment SET scan_status = 'REJECTED' WHERE id = ?",
        attachmentId);

    assertThat(scanService.scan(attachmentId)).isEqualTo(ScanStatus.REJECTED);
    assertThat(scanStatusOf(attachmentId)).isEqualTo("REJECTED");
  }

  @Test
  void 검사기를_쓸_수_없으면_검사_중으로_남고_복구_작업이_다시_검사한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long stuck =
        uploadAndGetId(
            member.cookie(), inquiryId, "stuck.pdf", pdf(StubAttachmentScanner.UNAVAILABLE_MARKER));
    long normal = uploadAndGetId(member.cookie(), inquiryId, "normal.pdf", pdf("정상"));

    assertThat(scanService.scan(stuck)).isEqualTo(ScanStatus.PENDING);
    assertThat(scanService.scanPending()).isZero();

    jdbcTemplate.update(
        "UPDATE support_inquiry_attachment SET created_at = now() - interval '5 minutes'");
    assertThat(scanService.scanPending()).isEqualTo(2);
    assertThat(scanStatusOf(stuck)).isEqualTo("PENDING");
    assertThat(scanStatusOf(normal)).isEqualTo("CLEAN");
  }

  @Test
  void 저장소에_파일이_없으면_거부로_판정한다() throws Exception {
    Member member = newMember("a@example.com");
    long inquiryId = createInquiry(member.cookie(), "BUG", "문의");
    long attachmentId = uploadAndGetId(member.cookie(), inquiryId, "a.pdf", pdf("정상"));
    String key =
        jdbcTemplate.queryForObject(
            "SELECT object_key FROM support_inquiry_attachment WHERE id = ?",
            String.class,
            attachmentId);
    objectStorage.delete(key);

    assertThat(scanService.scan(attachmentId)).isEqualTo(ScanStatus.REJECTED);
  }

  @Test
  void 다른_티켓의_첨부와_남의_첨부와_없는_첨부는_404이고_숫자가_아닌_ID는_400이다() throws Exception {
    Member member = newMember("a@example.com");
    Member other = newMember("b@example.com");
    long mine = createInquiry(member.cookie(), "BUG", "내 문의");
    long mineSecond = createInquiry(member.cookie(), "BUG", "내 둘째 문의");
    long theirs = createInquiry(other.cookie(), "BUG", "남의 문의");
    long attachmentId = uploadAndGetId(member.cookie(), mine, "a.pdf", pdf("정상"));
    long theirAttachment = uploadAndGetId(other.cookie(), theirs, "b.pdf", pdf("정상"));
    scanService.scan(attachmentId);
    scanService.scan(theirAttachment);

    assertError(
        getAs(
            "/api/support-inquiries/" + mineSecond + "/attachments/" + attachmentId,
            member.cookie()),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        getAs(
            "/api/support-inquiries/" + theirs + "/attachments/" + theirAttachment,
            member.cookie()),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        getAs("/api/support-inquiries/" + mine + "/attachments/99999", member.cookie()),
        ErrorCode.RESOURCE_NOT_FOUND);
    assertError(
        getAs("/api/support-inquiries/" + mine + "/attachments/abc", member.cookie()),
        ErrorCode.INVALID_REQUEST);
  }
}
