package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.SupportInquiryAssignRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.SupportInquiryOperatorNoteRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.SupportInquiryOperatorReplyRequest;
import com.sairo.be.domain.ops.dto.request.OpsSupportRequests.SupportInquiryPriorityRequest;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryAnswerResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryAssignResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryAttachmentResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryDetailResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryItem;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryNoteResponse;
import com.sairo.be.domain.ops.dto.response.OpsSupportResponses.OpsSupportInquiryPriorityResponse;
import com.sairo.be.domain.ops.service.OpsSupportInquiryService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops/support-inquiries")
@RequiresAccess(AccessPermission.OPERATOR)
@RequiresOperatorRole(OperatorRole.SUPPORT)
@Tag(name = "OPS", description = "지원 문의 처리")
public class OpsSupportInquiryController {

  private final OpsSupportInquiryService service;

  public OpsSupportInquiryController(OpsSupportInquiryService service) {
    this.service = service;
  }

  @Operation(
      summary = "문의 목록",
      description =
          "category·status·priority·assignedOperatorId·unassigned·overdue·officeId로 거른다. 마감 안 된 문의를"
              + " 답변 목표 시각 순으로 먼저 보인다.")
  @GetMapping
  public PageResponse<OpsSupportInquiryItem> list(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) Long assignedOperatorId,
      @RequestParam(required = false) Boolean unassigned,
      @RequestParam(required = false) Boolean overdue,
      @RequestParam(required = false) Long officeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return service.list(
        category,
        status,
        priority,
        assignedOperatorId,
        unassigned,
        overdue,
        officeId,
        PageQuery.of(page, size));
  }

  @Operation(summary = "문의 상세", description = "공개 답변·고객 메시지·내부 메모 이력과 첨부를 보인다. 조회는 감사 로그에 남긴다.")
  @GetMapping("/{inquiryId}")
  public OpsSupportInquiryDetailResponse detail(
      @PathVariable Long inquiryId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.detail(principal.operatorId(), inquiryId);
  }

  @Operation(summary = "담당자 배정", description = "활성 고객지원 운영자만 배정할 수 있다. 종료한 문의는 409다.")
  @PostMapping("/{inquiryId}/assign")
  public OpsSupportInquiryAssignResponse assign(
      @PathVariable Long inquiryId,
      @Valid @RequestBody SupportInquiryAssignRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.assign(principal.operatorId(), inquiryId, request.assigneeOperatorId());
  }

  @Operation(summary = "공개 답변 등록·마감", description = "답변마다 새 공개 메시지를 남기고 이전 답변은 고치지 않는다.")
  @PostMapping("/{inquiryId}/answer")
  public OpsSupportInquiryAnswerResponse answer(
      @PathVariable Long inquiryId,
      @Valid @RequestBody SupportInquiryOperatorReplyRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.answer(principal.operatorId(), inquiryId, request);
  }

  @Operation(summary = "내부 메모 추가", description = "고객 응답과 알림에 넣지 않는 내부 메모다.")
  @PostMapping("/{inquiryId}/notes")
  @ResponseStatus(HttpStatus.CREATED)
  public OpsSupportInquiryNoteResponse addNote(
      @PathVariable Long inquiryId,
      @Valid @RequestBody SupportInquiryOperatorNoteRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.addNote(principal.operatorId(), inquiryId, request.content());
  }

  @Operation(
      summary = "운영자 첨부 등록",
      description = "문의당 5개, 파일당 20MB, PDF·JPG·PNG만 받는다. 위반은 file 항목 400이다. If-Match를 쓰지 않는다.")
  @PostMapping(value = "/{inquiryId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public OpsSupportInquiryAttachmentResponse upload(
      @PathVariable Long inquiryId,
      @RequestPart(value = "file", required = false) MultipartFile file,
      @RequestParam(value = "messageId", required = false) String messageId,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.upload(principal.operatorId(), inquiryId, file, messageId);
  }

  @Operation(summary = "검사 완료 첨부 다운로드", description = "검사 중이거나 거부된 첨부는 409 ATTACHMENT_NOT_READY다.")
  @GetMapping("/{inquiryId}/attachments/{attachmentId}")
  public ResponseEntity<byte[]> download(
      @PathVariable Long inquiryId,
      @PathVariable Long attachmentId,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    OpsSupportInquiryService.Download download =
        service.download(principal.operatorId(), inquiryId, attachmentId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.mediaType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore())
        .body(download.content());
  }

  @Operation(
      summary = "우선순위 변경",
      description = "If-Match에 현재 version을 보낸다. 첫 공개 답변 전이면 답변 목표 시각을 다시 계산한다.")
  @PatchMapping("/{inquiryId}/priority")
  public OpsSupportInquiryPriorityResponse changePriority(
      @PathVariable Long inquiryId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody SupportInquiryPriorityRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.changePriority(principal.operatorId(), inquiryId, ifMatch, request);
  }
}
