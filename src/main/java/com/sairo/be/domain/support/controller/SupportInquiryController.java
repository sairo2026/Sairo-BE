package com.sairo.be.domain.support.controller;

import com.sairo.be.domain.support.dto.response.SupportInquiryAttachmentResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryCreateResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryListItem;
import com.sairo.be.domain.support.dto.response.SupportInquiryReplyResponse;
import com.sairo.be.domain.support.dto.response.SupportInquiryResponse;
import com.sairo.be.domain.support.service.SupportAttachmentService;
import com.sairo.be.domain.support.service.SupportInquiryService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/support-inquiries")
@Tag(name = "SUPPORT", description = "서비스 이용 문의와 지원 티켓")
public class SupportInquiryController {

  private final SupportInquiryService inquiryService;
  private final SupportAttachmentService attachmentService;

  public SupportInquiryController(
      SupportInquiryService inquiryService, SupportAttachmentService attachmentService) {
    this.inquiryService = inquiryService;
    this.attachmentService = attachmentService;
  }

  @Operation(
      summary = "문의 접수",
      description = "로그인한 사용자가 문의를 접수한다. 사무소 소속은 세션에서 정하며 요청 본문으로 받지 않는다.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public SupportInquiryCreateResponse create(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return inquiryService.create(principal, body);
  }

  @Operation(summary = "본인 문의 이력", description = "page, size(25·50·100)를 받는다. 접수 최신순이다.")
  @GetMapping("/me")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public PageResponse<SupportInquiryListItem> list(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return inquiryService.list(principal, page, size);
  }

  @Operation(
      summary = "본인 지원 티켓 상세",
      description = "공개 메시지와 공개 첨부만 돌려준다. 운영 내부 메모와 그 첨부는 어떤 경로로도 포함하지 않는다.")
  @GetMapping("/{inquiryId}")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public SupportInquiryResponse detail(
      @PathVariable Long inquiryId, @AuthenticationPrincipal StaffPrincipal principal) {
    return inquiryService.detail(principal, inquiryId);
  }

  @Operation(
      summary = "본인 지원 티켓에 추가 내용 기록",
      description = "완료 티켓은 접수로 재개하고 보완 대기·답변됨 티켓은 담당 운영자가 있으면 처리 중, 없으면 접수로 되돌린다.")
  @PostMapping("/{inquiryId}/replies")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public SupportInquiryReplyResponse reply(
      @PathVariable Long inquiryId,
      @RequestBody JsonNode body,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return inquiryService.reply(principal, inquiryId, body);
  }

  @Operation(
      summary = "티켓 첨부 등록",
      description = "PDF·JPG·PNG, 파일당 20MB, 티켓당 10개. 등록 직후 검사 중(PENDING)이며 검사를 통과한 파일만 내려받을 수 있다.")
  @PostMapping(value = "/{inquiryId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public SupportInquiryAttachmentResponse upload(
      @PathVariable Long inquiryId,
      @RequestPart(value = "file", required = false) MultipartFile file,
      @RequestParam(value = "messageId", required = false) String messageId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return attachmentService.upload(principal, inquiryId, file, messageId);
  }

  @Operation(
      summary = "검사 완료된 첨부 다운로드",
      description = "검사 중인 첨부는 409 ATTACHMENT_NOT_READY, 보안 검사에서 차단된 첨부는 409 ATTACHMENT_REJECTED다.")
  @GetMapping("/{inquiryId}/attachments/{attachmentId}")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public ResponseEntity<byte[]> download(
      @PathVariable Long inquiryId,
      @PathVariable Long attachmentId,
      @AuthenticationPrincipal StaffPrincipal principal) {
    SupportAttachmentService.Download download =
        attachmentService.download(principal, inquiryId, attachmentId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.mediaType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(download.fileName(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore())
        .body(download.content());
  }
}
