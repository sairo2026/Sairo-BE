package com.sairo.be.domain.visit.controller;

import com.sairo.be.domain.visit.dto.response.GuestPrivacyIntakeResponse;
import com.sairo.be.domain.visit.service.GuestPrivacyIntakeService;
import com.sairo.be.global.security.ApproverPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import com.sairo.be.global.web.ClientRequestInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@Tag(name = "RESPOND", description = "승인자 계정")
public class ApproverPortalPrivacyIntakeController {

  private final GuestPrivacyIntakeService intakeService;

  public ApproverPortalPrivacyIntakeController(GuestPrivacyIntakeService intakeService) {
    this.intakeService = intakeService;
  }

  @Operation(
      summary = "승인자 세션 개인정보 권리 요청 접수",
      description = "링크 토큰 대신 출입승인 ID를 받고 승인자 프로필과의 연결이 활성인지 확인한다. 안내 버전과 요청 한도는 링크 경로와 같다.")
  @PostMapping("/api/approver-portal/privacy-intakes")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresAccess(AccessPermission.APPROVER_SESSION)
  public GuestPrivacyIntakeResponse receive(
      @RequestBody JsonNode body,
      @AuthenticationPrincipal ApproverPrincipal principal,
      HttpServletRequest request,
      HttpServletResponse response) {
    response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    ClientRequestInfo client = ClientRequestInfo.from(request);
    return intakeService.receiveFromPortal(
        principal.profileId(), body, client.ipAddress(), client.userAgent());
  }
}
