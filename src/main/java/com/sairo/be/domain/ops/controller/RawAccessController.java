package com.sairo.be.domain.ops.controller;

import com.sairo.be.domain.ops.dto.request.RawAccessRequests.RawAccessRequestCreateRequest;
import com.sairo.be.domain.ops.dto.request.RawAccessRequests.RawAccessRequestRejectRequest;
import com.sairo.be.domain.ops.dto.request.RawAccessRequests.RawAccessRevokeRequest;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessApproveResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessContentResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessCreateResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessDetailResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessExecuteResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessItem;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessRejectResponse;
import com.sairo.be.domain.ops.dto.response.RawAccessResponses.RawAccessRevokeResponse;
import com.sairo.be.domain.ops.service.RawAccessService;
import com.sairo.be.global.security.OperatorPrincipal;
import com.sairo.be.global.security.OperatorRole;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.security.access.RequiresOperatorRole;
import com.sairo.be.global.support.PageQuery;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Profile("!migrate")
@RestController
@RequestMapping("/api/ops")
@RequiresAccess(AccessPermission.OPERATOR)
@Tag(name = "OPS", description = "원문 접근 승인·실행")
public class RawAccessController {

  private final RawAccessService service;

  public RawAccessController(RawAccessService service) {
    this.service = service;
  }

  @Operation(
      summary = "원문 접근 승인 요청 생성",
      description = "열람(RAW_VIEW)과 다운로드(RAW_DOWNLOAD)는 따로 요청하고 따로 승인받는다.")
  @PostMapping("/raw-access-requests")
  @ResponseStatus(HttpStatus.CREATED)
  public RawAccessCreateResponse create(
      @Valid @RequestBody RawAccessRequestCreateRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.create(principal.operatorId(), request);
  }

  @Operation(
      summary = "원문 접근 요청 목록",
      description = "scope=MINE(기본)은 내가 요청자·수혜자인 건, APPROVABLE은 개인정보담당이 승인할 수 있는 대기 건이다.")
  @GetMapping("/raw-access-requests")
  public PageResponse<RawAccessItem> list(
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.list(principal, scope, status, PageQuery.of(page, size));
  }

  @Operation(summary = "원문 접근 요청 상세", description = "요청자·수혜자·개인정보담당·보안관리자만 조회한다.")
  @GetMapping("/raw-access-requests/{requestId}")
  public RawAccessDetailResponse detail(
      @PathVariable Long requestId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.detail(principal, requestId);
  }

  @Operation(summary = "원문 접근 승인", description = "요청자·수혜자가 아닌 개인정보담당만 승인한다. 승인 유효기간은 24시간이다.")
  @PostMapping("/raw-access-requests/{requestId}/approve")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public RawAccessApproveResponse approve(
      @PathVariable Long requestId, @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.approve(principal.operatorId(), requestId);
  }

  @Operation(summary = "원문 접근 거절", description = "요청자·수혜자가 아닌 개인정보담당이 사유와 함께 거절한다.")
  @PostMapping("/raw-access-requests/{requestId}/reject")
  @RequiresOperatorRole(OperatorRole.PRIVACY)
  public RawAccessRejectResponse reject(
      @PathVariable Long requestId,
      @Valid @RequestBody RawAccessRequestRejectRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal) {
    return service.reject(principal.operatorId(), requestId, request.reason());
  }

  @Operation(
      summary = "원문 접근 실행",
      description = "수혜자 본인이 5분 재인증 뒤 승인 1건당 1회 실행한다. 접근 창은 60분이고 다운로드 승인이면 비공개 파일을 만든다.")
  @PostMapping("/raw-access-requests/{requestId}/execute")
  public RawAccessExecuteResponse execute(
      @PathVariable Long requestId,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.execute(principal.operatorId(), requestId, session);
  }

  @Operation(summary = "원문 접근 회수", description = "5분 재인증이 필요하다. 회수하면 남은 열람과 파일 전달이 즉시 막힌다.")
  @PostMapping("/raw-access-grants/{grantId}/revoke")
  @RequiresOperatorRole(OperatorRole.SECURITY_ADMIN)
  public RawAccessRevokeResponse revoke(
      @PathVariable Long grantId,
      @Valid @RequestBody RawAccessRevokeRequest request,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return service.revoke(principal.operatorId(), grantId, request.reason(), session);
  }

  @Operation(
      summary = "원문 파일 1회 전달",
      description = "다운로드 승인을 실행한 운영자 본인이 5분 재인증 뒤 접근 창 안에 한 번만 받는다. 공개 URL은 없다.")
  @GetMapping("/raw-access-grants/{grantId}/file")
  public ResponseEntity<byte[]> file(
      @PathVariable Long grantId,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    RawAccessService.DownloadedFile file =
        service.download(principal.operatorId(), grantId, session);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file.fileName()).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-SHA256", file.sha256())
        .body(file.content());
  }

  @Operation(
      summary = "원문 보기 내용 조회",
      description = "열람 승인을 실행한 운영자 본인이 5분 재인증 뒤 접근 창 안에서 읽는다. 읽을 때마다 감사 이력을 남기고 응답은 저장하지 않는다.")
  @GetMapping("/raw-access-grants/{grantId}/content")
  public ResponseEntity<RawAccessContentResponse> content(
      @PathVariable Long grantId,
      @AuthenticationPrincipal OperatorPrincipal principal,
      HttpSession session) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(service.view(principal.operatorId(), grantId, session));
  }
}
