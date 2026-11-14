package com.sairo.be.domain.billing.controller;

import com.sairo.be.domain.billing.dto.response.OfficeCloseResponse;
import com.sairo.be.domain.billing.service.OfficeCloseService;
import com.sairo.be.global.security.StaffPrincipal;
import com.sairo.be.global.security.access.AccessPermission;
import com.sairo.be.global.security.access.RequiresAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Profile("!migrate")
@RestController
@Tag(name = "OFFICE", description = "사무소 기본정보와 정보 변경 신청")
public class OfficeCloseController {

  private final OfficeCloseService officeCloseService;

  public OfficeCloseController(OfficeCloseService officeCloseService) {
    this.officeCloseService = officeCloseService;
  }

  @Operation(
      summary = "사무소 종료 확인·실행",
      description = "대표의 10분 이내 재인증이 필요하다. 구독을 끝내고 모든 소속을 해제하며 대표에게 30일 반환권한을 발급한다.")
  @PostMapping("/api/offices/current/close")
  @RequiresAccess(AccessPermission.OWNER)
  public OfficeCloseResponse close(
      @AuthenticationPrincipal StaffPrincipal principal,
      HttpSession session,
      @RequestBody(required = false) JsonNode body) {
    return officeCloseService.close(principal, session, body);
  }
}
