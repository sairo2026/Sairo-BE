package com.sairo.be.domain.legal.controller;

import com.sairo.be.domain.legal.dto.response.PrivacyRequestCreateResponse;
import com.sairo.be.domain.legal.dto.response.PrivacyRequestListItem;
import com.sairo.be.domain.legal.dto.response.PrivacyRequestResponse;
import com.sairo.be.domain.legal.service.PrivacyRequestService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.support.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@RequestMapping("/api/privacy-requests")
@Tag(name = "SUPPORT", description = "개인정보 권리 요청")
public class PrivacyRequestController {

  private final PrivacyRequestService requestService;

  public PrivacyRequestController(PrivacyRequestService requestService) {
    this.requestService = requestService;
  }

  @Operation(
      summary = "개인정보 권리 요청 접수",
      description = "열람·정정·삭제·처리정지·동의철회·이전 중 하나를 접수한다. category=PRIVACY 지원 문의를 같은 거래로 만든다.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public PrivacyRequestCreateResponse create(
      @RequestBody JsonNode body, @AuthenticationPrincipal StaffPrincipal principal) {
    return requestService.create(principal, body);
  }

  @Operation(
      summary = "본인 권리 요청 이력",
      description = "status(선택), page, size(25·50·100)를 받는다. 접수 최신순이다.")
  @GetMapping("/me")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public PageResponse<PrivacyRequestListItem> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @AuthenticationPrincipal StaffPrincipal principal) {
    return requestService.list(principal, status, page, size);
  }

  @Operation(
      summary = "본인 권리 요청 상세",
      description = "처리 상태, 본인확인 결과, 기한, 결정 사유, 처리 주체를 돌려준다. 본인의 요청만 조회된다.")
  @GetMapping("/{requestId}")
  @RequiresAccess(AccessPermission.AUTHENTICATED)
  public PrivacyRequestResponse detail(
      @PathVariable Long requestId, @AuthenticationPrincipal StaffPrincipal principal) {
    return requestService.detail(principal, requestId);
  }
}
